#!/usr/bin/env python3
"""Offline DeepSeek coding agent for the LegacyPilot JSONL eval protocol."""

from __future__ import annotations

import argparse
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request


HOST = "127.0.0.1"
PORT = 8000
MAX_CONTEXT_BYTES = 300_000
MAX_FILE_BYTES = 64_000
MAX_CHANGED_FILES = 12
MAX_CHANGED_BYTES = 1_000_000
ALLOWED_ROOTS = ("src/main/java/", "src/main/resources/")


class UnixHTTPConnection(http.client.HTTPConnection):
    def __init__(self, socket_path: Path, timeout: int):
        super().__init__("localhost", timeout=timeout)
        self.socket_path = socket_path

    def connect(self) -> None:
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(self.timeout)
        connection.connect(str(self.socket_path))
        self.sock = connection


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--served-model-name", required=True)
    parser.add_argument("--reasoning-effort", required=True)
    parser.add_argument("--jsonl", action="store_true", required=True)
    return parser.parse_args()


def trusted_integer(name: str, default: int, minimum: int, maximum: int) -> int:
    value = int(os.environ.get(name, str(default)))
    if value < minimum or value > maximum:
        raise ValueError(f"{name} is outside the approved range")
    return value


def start_server(model: Path, served_name: str) -> tuple[subprocess.Popen[bytes], Path]:
    if not model.is_dir():
        raise ValueError("model weights are unavailable")
    parallel = trusted_integer("LEGACY_PILOT_TENSOR_PARALLEL_SIZE", 1, 1, 64)
    max_length = trusted_integer("LEGACY_PILOT_MAX_MODEL_LEN", 32768, 4096, 131072)
    log_path = Path("/tmp/vllm.log")
    log = log_path.open("wb")
    command = [
        sys.executable,
        "-m",
        "vllm.entrypoints.openai.api_server",
        "--model",
        str(model),
        "--served-model-name",
        served_name,
        "--host",
        HOST,
        "--port",
        str(PORT),
        "--tensor-parallel-size",
        str(parallel),
        "--max-model-len",
        str(max_length),
        "--dtype",
        "auto",
        "--trust-remote-code",
    ]
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
    return process, log_path


def local_request(path: str, payload: dict | None = None, timeout: int = 10) -> dict:
    socket_value = os.environ.get("LEGACY_PILOT_MODEL_SOCKET")
    if socket_value:
        socket_path = Path(socket_value)
        if not socket_path.is_socket():
            raise ValueError("approved model socket is unavailable")
        connection = UnixHTTPConnection(socket_path, timeout)
        try:
            body = None if payload is None else json.dumps(payload).encode("utf-8")
            connection.request(
                "GET" if body is None else "POST",
                path,
                body=body,
                headers={"Content-Type": "application/json"},
            )
            response = connection.getresponse()
            data = response.read(8 * 1024 * 1024)
            if response.status < 200 or response.status >= 300:
                raise RuntimeError(f"local model returned HTTP {response.status}")
            return json.loads(data) if data else {}
        finally:
            connection.close()
    url = f"http://{HOST}:{PORT}{path}"
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="GET" if data is None else "POST",
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=timeout) as response:
        body = response.read(8 * 1024 * 1024)
    return json.loads(body) if body else {}


def wait_until_ready(process: subprocess.Popen[bytes], log_path: Path) -> None:
    deadline = time.monotonic() + trusted_integer("LEGACY_PILOT_STARTUP_SECONDS", 900, 30, 1800)
    while time.monotonic() < deadline:
        if process.poll() is not None:
            tail = log_path.read_text("utf-8", errors="replace")[-4000:]
            raise RuntimeError(f"local model server exited during startup: {tail}")
        try:
            local_request("/health", timeout=2)
            return
        except (OSError, urllib.error.URLError, json.JSONDecodeError):
            time.sleep(2)
    raise TimeoutError("local model server did not become healthy")


def source_context(workspace: Path) -> str:
    retained: list[str] = []
    total = 0
    for root in (workspace / "src/main/java", workspace / "src/main/resources"):
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*")):
            if not path.is_file() or path.is_symlink():
                continue
            size = path.stat().st_size
            if size > MAX_FILE_BYTES or total + size > MAX_CONTEXT_BYTES:
                continue
            relative = path.relative_to(workspace).as_posix()
            content = path.read_text("utf-8", errors="replace")
            retained.append(f"\n--- {relative} ---\n{content}")
            total += len(content.encode("utf-8"))
    if not retained:
        raise ValueError("workspace contains no readable production source")
    return "".join(retained)


def completion(prompt: str, context: str, served_name: str) -> dict:
    system = (
        "You are a Java 21 maintenance agent inside an offline bank environment. "
        "Return only one JSON object with a files array. Each item must contain path and the "
        "complete replacement content. Change only production files under src/main/java or "
        "src/main/resources. Never change tests, pom.xml, build output, or unrelated files."
    )
    payload = {
        "model": served_name,
        "temperature": 0.1,
        "max_tokens": 8192,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": f"{prompt}\n\nWORKSPACE SOURCE:\n{context}"},
        ],
        "response_format": {"type": "json_object"},
    }
    return local_request("/v1/chat/completions", payload, timeout=trusted_integer(
        "LEGACY_PILOT_INFERENCE_SECONDS", 600, 30, 1800
    ))


def response_object(response: dict) -> dict:
    choices = response.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ValueError("model response has no choices")
    content = choices[0].get("message", {}).get("content")
    if not isinstance(content, str):
        raise ValueError("model response has no textual content")
    start, end = content.find("{"), content.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("model response does not contain a JSON object")
    value = json.loads(content[start : end + 1])
    if not isinstance(value, dict):
        raise ValueError("model patch payload is invalid")
    return value


def safe_target(workspace: Path, raw: str) -> Path:
    if not isinstance(raw, str) or not raw.startswith(ALLOWED_ROOTS):
        raise ValueError("model attempted to write outside production source")
    candidate = workspace / raw
    current = candidate
    while current != workspace:
        if current.is_symlink():
            raise ValueError("model attempted to traverse a symbolic link")
        current = current.parent
    target = candidate.resolve()
    if not any(
        target.is_relative_to((workspace / root).resolve())
        for root in ("src/main/java", "src/main/resources")
    ):
        raise ValueError("model attempted to escape production source")
    return target


def apply_files(workspace: Path, payload: dict) -> int:
    files = payload.get("files")
    if not isinstance(files, list) or not files or len(files) > MAX_CHANGED_FILES:
        raise ValueError("model patch file count is invalid")
    prepared: list[tuple[Path, bytes]] = []
    targets: set[Path] = set()
    total = 0
    for item in files:
        if not isinstance(item, dict) or not isinstance(item.get("content"), str):
            raise ValueError("model patch entry is invalid")
        target = safe_target(workspace, item.get("path"))
        if target in targets:
            raise ValueError("model returned the same file more than once")
        targets.add(target)
        content = item["content"].encode("utf-8")
        total += len(content)
        if total > MAX_CHANGED_BYTES:
            raise ValueError("model patch exceeds the byte budget")
        prepared.append((target, content))
    for target, content in prepared:
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix=".model-agent-", dir=target.parent)
        try:
            with os.fdopen(descriptor, "wb") as output:
                output.write(content)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, target)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
        print(json.dumps({"type": "item.completed", "path": target.relative_to(workspace).as_posix()}))
    return len(prepared)


def main() -> int:
    args = arguments()
    workspace = Path(args.workspace).resolve()
    prompt = sys.stdin.read(MAX_CONTEXT_BYTES)
    if not workspace.is_dir() or not prompt.strip():
        raise ValueError("workspace or prompt is invalid")
    process = None
    try:
        socket_value = os.environ.get("LEGACY_PILOT_MODEL_SOCKET")
        if socket_value:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                try:
                    local_request("/health", timeout=2)
                    break
                except (OSError, ValueError, RuntimeError):
                    time.sleep(1)
            else:
                raise TimeoutError("persistent local model socket did not become healthy")
        else:
            process, log_path = start_server(Path(args.model).resolve(), args.served_model_name)
            wait_until_ready(process, log_path)
        response = completion(prompt, source_context(workspace), args.served_model_name)
        apply_files(workspace, response_object(response))
        usage = response.get("usage") or {}
        output_tokens = max(0, int(usage.get("completion_tokens", 0)))
        reasoning_tokens = min(output_tokens, max(0, int(usage.get("reasoning_tokens", 0))))
        event = {
            "type": "turn.completed",
            "usage": {
                "input_tokens": max(0, int(usage.get("prompt_tokens", 0))),
                "cached_input_tokens": 0,
                "output_tokens": output_tokens,
                "reasoning_output_tokens": reasoning_tokens,
            },
        }
        print(json.dumps(event))
        return 0
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # The Java runner records a bounded provider error.
        print(f"DeepSeek agent failed: {error}", file=sys.stderr)
        raise SystemExit(1)
