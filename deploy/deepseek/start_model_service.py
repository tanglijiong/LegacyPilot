#!/usr/bin/env python3
"""Start a persistent, networkless vLLM service on a shared Unix socket."""

from __future__ import annotations

import argparse
import http.client
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time


PINNED_IMAGE = re.compile(r"[A-Za-z0-9./_-]+@sha256:[0-9a-f]{64}")
SHA256 = re.compile(r"[0-9a-f]{64}")
SAFE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", required=True)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--model-artifact-sha256", required=True)
    parser.add_argument("--socket-directory", type=Path, required=True)
    parser.add_argument("--served-model-name", required=True)
    parser.add_argument("--container-name", default="legacypilot-deepseek")
    parser.add_argument("--memory", default="24g")
    parser.add_argument("--cpus", type=int, default=8)
    parser.add_argument("--pids", type=int, default=1024)
    parser.add_argument("--gpus", default="all")
    parser.add_argument("--tensor-parallel-size", type=int, default=1)
    parser.add_argument("--max-model-length", type=int, default=32768)
    parser.add_argument("--startup-timeout-seconds", type=int, default=900)
    return parser.parse_args()


def validated(args: argparse.Namespace) -> tuple[Path, Path, Path]:
    docker = Path(args.docker) if "/" in args.docker else Path(shutil.which(args.docker) or "")
    weights = args.weights.expanduser().resolve()
    socket_directory = args.socket_directory.expanduser().resolve()
    if not docker.is_file() or not PINNED_IMAGE.fullmatch(args.image):
        raise ValueError("docker executable or pinned image is invalid")
    if not weights.is_dir() or not SHA256.fullmatch(args.model_artifact_sha256):
        raise ValueError("model weights or artifact digest is invalid")
    marker = weights / "MODEL_ARTIFACT_SHA256"
    if not marker.is_file() or marker.read_text("utf-8").strip() != args.model_artifact_sha256:
        raise ValueError("model artifact digest marker does not match")
    socket_directory.mkdir(parents=True, exist_ok=True)
    if (
        not os.access(socket_directory, os.W_OK)
        or (socket_directory / "vllm.sock").exists()
        or (socket_directory / "service-manifest.json").exists()
    ):
        raise ValueError("model socket directory is not writable or contains stale service state")
    if any(character in str(path) for path in (weights, socket_directory) for character in ",\n\r"):
        raise ValueError("model mount path is invalid")
    if not SAFE_NAME.fullmatch(args.container_name) or not SAFE_NAME.fullmatch(args.served_model_name):
        raise ValueError("model or container name is invalid")
    if not re.fullmatch(r"[1-9][0-9]*[kKmMgG]", args.memory):
        raise ValueError("model memory limit is invalid")
    if not 1 <= args.cpus <= 256 or not 64 <= args.pids <= 32768:
        raise ValueError("model process limits are invalid")
    if not re.fullmatch(r"none|all|[0-9]+(?:,[0-9]+)*", args.gpus):
        raise ValueError("GPU selection is invalid")
    if not 1 <= args.tensor_parallel_size <= 64 or not 4096 <= args.max_model_length <= 131072:
        raise ValueError("model parallelism or context limit is invalid")
    if not 30 <= args.startup_timeout_seconds <= 3600:
        raise ValueError("model startup timeout is invalid")
    return docker.resolve(), weights, socket_directory


def command(args: argparse.Namespace) -> list[str]:
    docker, weights, socket_directory = validated(args)
    result = [
        str(docker),
        "run",
        "--detach",
        "--rm",
        "--name",
        args.container_name,
        "--pull",
        "never",
        "--network",
        "none",
        "--read-only",
        "--cap-drop",
        "ALL",
        "--security-opt",
        "no-new-privileges",
        "--pids-limit",
        str(args.pids),
        "--memory",
        args.memory,
        "--memory-swap",
        args.memory,
        "--cpus",
        str(args.cpus),
        "--user",
        "1000:1000",
        "--tmpfs",
        "/tmp:rw,nosuid,nodev,size=4g",
        "--mount",
        f"type=bind,src={weights},dst=/models/model,readonly",
        "--mount",
        f"type=bind,src={socket_directory},dst=/run/legacy-pilot-model",
    ]
    if args.gpus != "none":
        result.extend(["--gpus", args.gpus if args.gpus == "all" else f"device={args.gpus}"])
    result.extend(
        [
            args.image,
            "python3",
            "-m",
            "vllm.entrypoints.openai.api_server",
            "--model",
            "/models/model",
            "--served-model-name",
            args.served_model_name,
            "--uds",
            "/run/legacy-pilot-model/vllm.sock",
            "--tensor-parallel-size",
            str(args.tensor_parallel_size),
            "--max-model-len",
            str(args.max_model_length),
            "--dtype",
            "auto",
            "--trust-remote-code",
        ]
    )
    return result


def write_service_manifest(args: argparse.Namespace) -> Path:
    """Atomically attest the exact service configuration accepted by Docker."""
    target = args.socket_directory.expanduser().resolve() / "service-manifest.json"
    temporary = target.with_suffix(".json.tmp")
    manifest = {
        "image": args.image,
        "model": args.served_model_name,
        "modelArtifactSha256": args.model_artifact_sha256,
        "memory": args.memory,
        "cpus": args.cpus,
        "pids": args.pids,
        "gpus": args.gpus,
        "tensorParallelSize": args.tensor_parallel_size,
        "maxModelLength": args.max_model_length,
    }
    temporary.write_text(json.dumps(manifest, indent=2) + "\n", "utf-8")
    os.replace(temporary, target)
    return target


class UnixHTTPConnection(http.client.HTTPConnection):
    def __init__(self, socket_path: Path, timeout: int):
        super().__init__("localhost", timeout=timeout)
        self.socket_path = socket_path

    def connect(self) -> None:
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(self.timeout)
        connection.connect(str(self.socket_path))
        self.sock = connection


def service_is_healthy(socket_path: Path) -> bool:
    if not socket_path.is_socket():
        return False
    connection = UnixHTTPConnection(socket_path, 2)
    try:
        connection.request("GET", "/health")
        response = connection.getresponse()
        response.read(64 * 1024)
        return 200 <= response.status < 300
    except (OSError, http.client.HTTPException):
        return False
    finally:
        connection.close()


def wait_until_healthy(args: argparse.Namespace) -> None:
    socket_path = args.socket_directory.expanduser().resolve() / "vllm.sock"
    deadline = time.monotonic() + args.startup_timeout_seconds
    while time.monotonic() < deadline:
        if service_is_healthy(socket_path):
            return
        time.sleep(2)
    raise TimeoutError("model service did not become healthy before the approved timeout")


def main() -> int:
    args = arguments()
    docker_command = command(args)
    completed = subprocess.run(docker_command, check=False)
    if completed.returncode == 0:
        try:
            wait_until_healthy(args)
            write_service_manifest(args)
        except Exception:
            subprocess.run(
                [docker_command[0], "stop", args.container_name],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            raise
    return completed.returncode


if __name__ == "__main__":
    raise SystemExit(main())
