import argparse
import json
import os
from pathlib import Path
import socketserver
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler

import model_agent
import start_model_service


class OfflineAgentTest(unittest.TestCase):
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
