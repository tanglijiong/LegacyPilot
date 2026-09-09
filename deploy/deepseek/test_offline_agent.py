import argparse
import json
import os
from pathlib import Path
import socketserver
import tempfile
import threading
import unittest
from unittest import mock
from http.server import BaseHTTPRequestHandler
from http.server import HTTPServer

import model_agent
import start_model_service


class OfflineAgentTest(unittest.TestCase):
    def test_failed_attempt_can_restore_allowed_files_transactionally(self):
        with tempfile.TemporaryDirectory() as root:
            workspace = Path(root)
            existing = workspace / "src/main/java/example/Existing.java"
            created = workspace / "src/main/java/example/Created.java"
            existing.parent.mkdir(parents=True)
            existing.write_text("original\n", "utf-8")
            paths = [
                "src/main/java/example/Existing.java",
                "src/main/java/example/Created.java",
            ]

            snapshot = model_agent.snapshot_allowed_files(workspace, paths)
            existing.write_text("broken\n", "utf-8")
            created.write_text("new\n", "utf-8")
            model_agent.restore_allowed_files(workspace, snapshot)

            self.assertEqual("original\n", existing.read_text("utf-8"))
            self.assertFalse(created.exists())

    def test_planning_failure_uses_a_conservative_fallback(self):
        usage = {"input_tokens": 0, "output_tokens": 0, "reasoning_output_tokens": 0}
        with mock.patch.object(
            model_agent, "planning_completion", side_effect=ValueError("invalid plan")
        ):
            plan, result = model_agent.resilient_plan(
                "requirement", "context", "model", [], [], [], usage
            )

        self.assertIn("fallback:", result)
        self.assertTrue(plan["plan"])
        self.assertEqual(0, usage["input_tokens"])

    def test_main_passes_governed_task_constraints_to_completion(self):
        with tempfile.TemporaryDirectory() as root:
            workspace = Path(root)
            source = workspace / "src/main/java/example/Value.java"
            source.parent.mkdir(parents=True)
            source.write_text("package example; final class Value {}\n", "utf-8")
            allowed = "src/main/java/example/Value.java"
            parsed = argparse.Namespace(
                workspace=str(workspace),
                model="local-model",
                served_model_name="local-model",
                reasoning_effort="low",
                allowed_file=[allowed],
                required_file=[allowed],
                acceptance=[f"CONTAINS {allowed} => Value"],
                jsonl=True,
            )
            response = {
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "files": [
                                        {
                                            "path": allowed,
                                            "content": "package example; final class Value {}\n",
                                        }
                                    ]
                                }
                            )
                        }
                    }
                ],
                "usage": {"prompt_tokens": 10, "completion_tokens": 5},
            }
            plan_response = {
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {"plan": ["preserve Value"], "risks": [], "preserved_api": []}
                            )
                        }
                    }
                ],
                "usage": {"prompt_tokens": 4, "completion_tokens": 2},
            }
            old_base = os.environ.get("LEGACY_PILOT_MODEL_BASE_URL")
            os.environ["LEGACY_PILOT_MODEL_BASE_URL"] = "http://127.0.0.1:12434/v1"
            try:
                with (
                    mock.patch.object(model_agent, "arguments", return_value=parsed),
                    mock.patch.object(model_agent.sys, "stdin", mock.Mock(read=lambda _: "prompt")),
                    mock.patch.object(
                        model_agent,
                        "local_request",
                        side_effect=[{"data": []}, plan_response, response],
                    ),
                    mock.patch.object(model_agent, "build_failures", return_value=[]),
                ):
                    self.assertEqual(0, model_agent.main())
            finally:
                if old_base is None:
                    os.environ.pop("LEGACY_PILOT_MODEL_BASE_URL", None)
                else:
                    os.environ["LEGACY_PILOT_MODEL_BASE_URL"] = old_base

            self.assertEqual("package example; final class Value {}\n", source.read_text("utf-8"))

    def test_calls_an_openai_compatible_loopback_endpoint(self):
        paths = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                paths.append(self.path)
                body = b'{"data":[]}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        previous = os.environ.pop("LEGACY_PILOT_MODEL_SOCKET", None)
        old_base = os.environ.get("LEGACY_PILOT_MODEL_BASE_URL")
        os.environ["LEGACY_PILOT_MODEL_BASE_URL"] = (
            f"http://127.0.0.1:{server.server_port}/engines/v1"
        )
        try:
            self.assertEqual({"data": []}, model_agent.local_request("/v1/models", timeout=2))
            self.assertEqual(["/engines/v1/models"], paths)
            os.environ["LEGACY_PILOT_MODEL_BASE_URL"] = "https://localhost:12434/v1"
            with self.assertRaises(ValueError):
                model_agent.local_request("/v1/models", timeout=2)
        finally:
            server.shutdown()
            server.server_close()
            if previous is not None:
                os.environ["LEGACY_PILOT_MODEL_SOCKET"] = previous
            if old_base is None:
                os.environ.pop("LEGACY_PILOT_MODEL_BASE_URL", None)
            else:
                os.environ["LEGACY_PILOT_MODEL_BASE_URL"] = old_base

    def test_calls_the_model_only_through_the_approved_unix_socket(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                body = b'{"status":"ok"}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format, *args):
                pass

        with tempfile.TemporaryDirectory() as root:
            socket_path = Path(root) / "vllm.sock"
            server = socketserver.UnixStreamServer(str(socket_path), Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            previous = os.environ.get("LEGACY_PILOT_MODEL_SOCKET")
            os.environ["LEGACY_PILOT_MODEL_SOCKET"] = str(socket_path)
            try:
                self.assertEqual(
                    {"status": "ok"}, model_agent.local_request("/health", timeout=2)
                )
                self.assertTrue(start_model_service.service_is_healthy(socket_path))
            finally:
                server.shutdown()
                server.server_close()
                if previous is None:
                    os.environ.pop("LEGACY_PILOT_MODEL_SOCKET", None)
                else:
                    os.environ["LEGACY_PILOT_MODEL_SOCKET"] = previous

    def test_applies_only_bounded_production_files(self):
        with tempfile.TemporaryDirectory() as root:
            workspace = Path(root).resolve()
            (workspace / "src/main/java/example").mkdir(parents=True)
            payload = {
                "files": [
                    {
                        "path": "src/main/java/example/Value.java",
                        "content": "package example; final class Value {}\n",
                    }
                ]
            }
            self.assertEqual(1, model_agent.apply_files(workspace, payload))
            self.assertTrue((workspace / "src/main/java/example/Value.java").is_file())
            with self.assertRaises(ValueError):
                model_agent.apply_files(
                    workspace,
                    payload,
                    ["src/main/java/example/Another.java"],
                )
            with self.assertRaises(ValueError):
                model_agent.apply_files(
                    workspace,
                    payload,
                    ["src/main/java/example/Value.java", "src/main/java/example/Required.java"],
                    ["src/main/java/example/Value.java", "src/main/java/example/Required.java"],
                )
            with self.assertRaises(ValueError):
                model_agent.apply_files(
                    workspace, {"files": [{"path": "pom.xml", "content": "unsafe"}]}
                )
            (workspace / "src/main/java/escape").symlink_to(workspace / "pom.xml")
            with self.assertRaises(ValueError):
                model_agent.apply_files(
                    workspace,
                    {"files": [{"path": "src/main/java/escape", "content": "unsafe"}]},
                )

    def test_extracts_json_without_accepting_missing_choices(self):
        response = {"choices": [{"message": {"content": "```json\n{\"files\": []}\n```"}}]}
        self.assertEqual({"files": []}, model_agent.response_object(response))
        with self.assertRaises(ValueError):
            model_agent.response_object({})

    def test_reports_public_acceptance_failures(self):
        with tempfile.TemporaryDirectory() as root:
            workspace = Path(root)
            target = workspace / "src/main/java/example/Value.java"
            target.parent.mkdir(parents=True)
            target.write_text('final class Value { static final String CODE = "OK"; }\n', "utf-8")
            checks = [
                "FILE_EXISTS src/main/java/example/Value.java",
                "CONTAINS src/main/java/example/Value.java => CODE",
                "MATCHES_REGEX src/main/java/example/Value.java => String\\s+CODE",
                "NOT_CONTAINS src/main/java/example/Value.java => INVALID",
                "CONTAINS src/main/java/example/Value.java => MISSING",
            ]

            self.assertEqual(
                [
                    "CONTAINS failed for src/main/java/example/Value.java: MISSING"
                ],
                model_agent.public_failures(workspace, checks),
            )

    def test_service_command_is_pinned_and_networkless(self):
        with tempfile.TemporaryDirectory() as root:
            directory = Path(root)
            docker = directory / "docker"
            docker.write_text("#!/bin/sh\n", "utf-8")
            docker.chmod(0o755)
            weights = directory / "weights"
            weights.mkdir()
            digest = "b" * 64
            (weights / "MODEL_ARTIFACT_SHA256").write_text(digest + "\n", "utf-8")
            args = argparse.Namespace(
                docker=str(docker),
                image="registry.bank.local/vllm@sha256:" + "a" * 64,
                weights=weights,
                model_artifact_sha256=digest,
                socket_directory=directory / "socket",
                served_model_name="deepseek-coder-v2-lite",
                container_name="legacypilot-deepseek",
                memory="24g",
                cpus=8,
                pids=2048,
                gpus="all",
                tensor_parallel_size=1,
                max_model_length=32768,
                startup_timeout_seconds=900,
            )
            command = start_model_service.command(args)
            self.assertEqual("never", command[command.index("--pull") + 1])
            self.assertEqual("none", command[command.index("--network") + 1])
            self.assertIn("/run/legacy-pilot-model/vllm.sock", command)
            self.assertNotIn("api.deepseek.com", " ".join(command))
            manifest = json.loads(
                start_model_service.write_service_manifest(args).read_text("utf-8")
            )
            self.assertEqual(args.image, manifest["image"])
            self.assertEqual(digest, manifest["modelArtifactSha256"])
            self.assertEqual(32768, manifest["maxModelLength"])


if __name__ == "__main__":
    unittest.main()
