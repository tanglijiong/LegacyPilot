#!/usr/bin/env python3
"""Offline DeepSeek coding agent for the LegacyPilot JSONL eval protocol."""

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
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


HOST = "127.0.0.1"
PORT = 8000
# Keeps the prompt plus an 8K-token patch response within a 32K local context window.
MAX_CONTEXT_BYTES = 80_000
MAX_FILE_BYTES = 64_000
MAX_CHANGED_FILES = 12
MAX_CHANGED_BYTES = 1_000_000
ALLOWED_ROOTS = ("src/main/java/", "src/main/resources/")
TRACE_DIRECTORY = ".legacy-pilot"


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
    parser.add_argument("--allowed-file", action="append", default=[])
    parser.add_argument("--required-file", action="append", default=[])
    parser.add_argument("--acceptance", action="append", default=[])
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
    base_url = os.environ.get("LEGACY_PILOT_MODEL_BASE_URL")
    if base_url:
        parsed = urllib.parse.urlsplit(base_url)
        if (
            parsed.scheme != "http"
            or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
            or parsed.port is None
            or parsed.username is not None
            or parsed.password is not None
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("local model base URL must be an explicit loopback HTTP endpoint")
        suffix = path[3:] if path.startswith("/v1/") else path
        url = base_url.rstrip("/") + suffix
    else:
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


def source_context(workspace: Path) -> tuple[str, list[dict[str, int | str]]]:
    retained: list[str] = []
    selected: list[dict[str, int | str]] = []
    total = 0
    candidates: list[Path] = []
    pom = workspace / "pom.xml"
    if pom.is_file() and not pom.is_symlink():
        candidates.append(pom)
    for root in (
        workspace / "src/main/java",
        workspace / "src/main/resources",
        workspace / "src/test/java",
    ):
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*")):
            if path.is_file() and not path.is_symlink():
                candidates.append(path)
    for path in candidates:
        size = path.stat().st_size
        if size > MAX_FILE_BYTES or total + size > MAX_CONTEXT_BYTES:
            continue
        relative = path.relative_to(workspace).as_posix()
        content = path.read_text("utf-8", errors="replace")
        retained.append(f"\n--- READ-ONLY CONTEXT: {relative} ---\n{content}")
        content_bytes = len(content.encode("utf-8"))
        selected.append({"path": relative, "bytes": content_bytes})
        total += content_bytes
    if not retained:
        raise ValueError("workspace contains no readable production source")
    return "".join(retained), selected


def planning_completion(
    prompt: str,
    context: str,
    served_name: str,
    allowed_files: list[str],
    required_files: list[str],
    acceptance: list[str],
) -> dict:
    payload = {
        "model": served_name,
        "temperature": 0.0,
        "max_tokens": 2048,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are planning a Java 21 maintenance change. Return only a JSON object "
                    "with a plan array, risks array, and preserved_api array. Inspect every caller "
                    "and test in the supplied workspace. Identify exact existing constructors, "
                    "methods, return types, and top-level types that must remain source-compatible. "
                    "Do not write code and do not invent requirements."
                ),
            },
            {
                "role": "user",
                "content": (
                    f"REQUIREMENT:\n{prompt}\n\nALLOWED FILES:\n"
                    + "\n".join(f"- {path}" for path in allowed_files)
                    + "\n\nREQUIRED FILES:\n"
                    + "\n".join(f"- {path}" for path in required_files)
                    + "\n\nPUBLIC CHECKS:\n"
                    + "\n".join(f"- {check}" for check in acceptance)
                    + f"\n\nWORKSPACE SOURCE:\n{context}"
                ),
            },
        ],
        "response_format": {"type": "json_object"},
    }
    return local_request("/v1/chat/completions", payload, timeout=trusted_integer(
        "LEGACY_PILOT_INFERENCE_SECONDS", 600, 30, 1800
    ))


def completion(
    prompt: str,
    context: str,
    served_name: str,
    allowed_files: list[str],
    required_files: list[str],
    acceptance: list[str],
    plan: dict,
    feedback: str,
) -> dict:
    allowed = "\n".join(f"- {path}" for path in allowed_files) or "- production source files only"
    required = "\n".join(f"- {path}" for path in required_files) or "- none specified"
    check_lines: list[str] = []
    for check in acceptance:
        check_type, _, remainder = check.partition(" ")
        raw_path, separator, expected = remainder.partition(" => ")
        if check_type == "CONTAINS" and separator:
            check_lines.append(
                f"- REQUIRED EXACT SOURCE TEXT in {raw_path}: {json.dumps(expected)}"
            )
        elif check_type == "MATCHES_REGEX" and separator:
            check_lines.append(f"- REQUIRED SOURCE REGEX in {raw_path}: {expected}")
        else:
            check_lines.append(f"- {check}")
    checks = "\n".join(check_lines) or "- compile and pass tests"
    system = (
        "You are a Java 21 maintenance agent running in a local offline environment. "
        "Return only one JSON object with a files array. Each item must contain path and the "
        "complete, compilable replacement content including package, imports, and enclosing type. "
        "Never return a fragment. Change only the explicitly allowed production files. Never "
        "change tests, pom.xml, build output, or unrelated files. Satisfy every public acceptance "
        "check exactly, including named constants and error codes. An exact error-code token must "
        "be used as an exception message or named constant, not paraphrased. An exact Java member "
        "such as ZoneOffset.UTC must be used in executable code with the necessary import. Create "
        "every required new file."
        " Preserve all existing public and package-visible constructors, methods, return types, "
        "and top-level types unless the requirement explicitly asks to replace them. Add overloads "
        "instead of removing an existing API. Keep exactly one public top-level Java type per file, "
        "and keep its name equal to the filename. Check imports, mutability, null handling, callers, "
        "and boundary values before returning."
    )
    payload = {
        "model": served_name,
        "temperature": 0.1,
        "max_tokens": 8192,
        "messages": [
            {"role": "system", "content": system},
            {
                "role": "user",
                "content": (
                    f"{prompt}\n\nALLOWED PRODUCTION FILES:\n{allowed}"
                    f"\n\nREQUIRED CHANGED OR CREATED FILES:\n{required}"
                    f"\n\nPUBLIC ACCEPTANCE CHECKS:\n{checks}"
                    f"\n\nWORKSPACE SOURCE:\n{context}"
                    f"\n\nAPPROVED IMPLEMENTATION PLAN:\n{json.dumps(plan, ensure_ascii=False)}"
                    f"\n\nFINAL NON-NEGOTIABLE CHECKLIST:\n{checks}"
                    f"\nEvery required file must be returned with complete content.{feedback}"
                ),
            },
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


def resilient_plan(
    prompt: str,
    context: str,
    served_name: str,
    allowed_files: list[str],
    required_files: list[str],
    acceptance: list[str],
    usage: dict[str, int],
) -> tuple[dict, str]:
    try:
        response = planning_completion(
            prompt, context, served_name, allowed_files, required_files, acceptance
        )
        add_usage(usage, response)
        return response_object(response), "completed"
    except (ValueError, OSError, json.JSONDecodeError, urllib.error.URLError) as error:
        return (
            {
                "plan": [
                    "Inspect callers and tests, preserve existing APIs, implement the requirement, "
                    "then compile and test the complete workspace."
                ],
                "risks": ["Planning model output was unavailable; use conservative maintenance."],
                "preserved_api": [],
            },
            f"fallback: {error}",
        )


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


def apply_files(
    workspace: Path,
    payload: dict,
    allowed_files: list[str] | None = None,
    required_files: list[str] | None = None,
) -> int:
    files = payload.get("files")
    if not isinstance(files, list) or not files or len(files) > MAX_CHANGED_FILES:
        raise ValueError("model patch file count is invalid")
    prepared: list[tuple[Path, bytes]] = []
    targets: set[Path] = set()
    total = 0
    for item in files:
        if not isinstance(item, dict) or not isinstance(item.get("content"), str):
            raise ValueError("model patch entry is invalid")
        raw_path = item.get("path")
        if allowed_files and raw_path not in allowed_files:
            raise ValueError("model attempted to write a file outside the task scope")
        target = safe_target(workspace, raw_path)
        if target in targets:
            raise ValueError("model returned the same file more than once")
        targets.add(target)
        content = item["content"].encode("utf-8")
        total += len(content)
        if total > MAX_CHANGED_BYTES:
            raise ValueError("model patch exceeds the byte budget")
        prepared.append((target, content))
    missing = set(required_files or []) - {
        target.relative_to(workspace).as_posix() for target, _ in prepared
    }
    if missing:
        raise ValueError("model omitted required files: " + ", ".join(sorted(missing)))
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


def public_failures(workspace: Path, checks: list[str]) -> list[str]:
    failures: list[str] = []
    for check in checks:
        check_type, _, remainder = check.partition(" ")
        raw_path, separator, expected = remainder.partition(" => ")
        path = workspace / raw_path
        content = path.read_text("utf-8", errors="replace") if path.is_file() else ""
        passed = False
        if check_type == "FILE_EXISTS":
            passed = path.is_file()
        elif check_type == "FILE_NOT_EXISTS":
            passed = not path.exists()
        elif check_type == "CONTAINS" and separator:
            passed = expected in content
        elif check_type == "NOT_CONTAINS" and separator:
            passed = expected not in content
        elif check_type == "MATCHES_REGEX" and separator:
            passed = re.search(expected, content) is not None
        elif check_type == "NOT_MATCHES_REGEX" and separator:
            passed = re.search(expected, content) is None
        if not passed:
            failures.append(f"{check_type} failed for {raw_path}: {expected}".rstrip())
    return failures


def build_failures(workspace: Path) -> list[str]:
    wrapper = workspace / "mvnw"
    executable = str(wrapper) if wrapper.is_file() else shutil.which("mvn")
    if executable is None:
        return ["Maven verification is unavailable in the agent environment"]
    try:
        result = subprocess.run(
            [executable, "--batch-mode", "--no-transfer-progress", "test"],
            cwd=workspace,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=90,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return [f"Maven verification could not complete: {error}"]
    if result.returncode == 0:
        return []
    output = result.stdout.decode("utf-8", errors="replace")[-6000:]
    return [f"Maven verification failed:\n{output}"]


def add_usage(total: dict[str, int], response: dict) -> None:
    usage = response.get("usage") or {}
    total["input_tokens"] += max(0, int(usage.get("prompt_tokens", 0)))
    total["output_tokens"] += max(0, int(usage.get("completion_tokens", 0)))
    total["reasoning_output_tokens"] += max(0, int(usage.get("reasoning_tokens", 0)))


def write_trace(workspace: Path, trace: dict) -> None:
    directory = workspace / TRACE_DIRECTORY
    directory.mkdir(parents=True, exist_ok=True)
    target = directory / "agent-trace.json"
    descriptor, temporary = tempfile.mkstemp(prefix=".agent-trace-", dir=directory)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(trace, output, ensure_ascii=False, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main() -> int:
    args = arguments()
    workspace = Path(args.workspace).resolve()
    prompt = sys.stdin.read(MAX_CONTEXT_BYTES)
    if not workspace.is_dir() or not prompt.strip():
        raise ValueError("workspace or prompt is invalid")
    process = None
    try:
        socket_value = os.environ.get("LEGACY_PILOT_MODEL_SOCKET")
        base_url = os.environ.get("LEGACY_PILOT_MODEL_BASE_URL")
        if socket_value or base_url:
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                try:
                    local_request("/v1/models" if base_url else "/health", timeout=2)
                    break
                except (OSError, ValueError, RuntimeError):
                    time.sleep(1)
            else:
                raise TimeoutError("persistent local model socket did not become healthy")
        else:
            process, log_path = start_server(Path(args.model).resolve(), args.served_model_name)
            wait_until_ready(process, log_path)
        usage = {"input_tokens": 0, "output_tokens": 0, "reasoning_output_tokens": 0}
        context, selected_context = source_context(workspace)
        plan, planning_result = resilient_plan(
            prompt,
            context,
            args.served_model_name,
            args.allowed_file,
            args.required_file,
            args.acceptance,
            usage,
        )
        trace = {
            "requirement": prompt,
            "selectedContext": selected_context,
            "plan": plan,
            "toolCalls": [{"tool": "model.plan", "result": planning_result}],
            "attempts": [],
            "model": args.served_model_name,
            "reasoningEffort": args.reasoning_effort,
        }
        feedback = ""
        touched_files: set[str] = set()
        for attempt in range(3):
            attempt_trace: dict[str, object] = {"attempt": attempt + 1}
            try:
                context, selected_context = source_context(workspace)
                response = completion(
                    prompt,
                    context,
                    args.served_model_name,
                    args.allowed_file,
                    args.required_file,
                    args.acceptance,
                    plan,
                    feedback,
                )
                add_usage(usage, response)
                payload = response_object(response)
                changed_count = apply_files(
                    workspace,
                    payload,
                    args.allowed_file,
                    [],
                )
                modified_files = [item["path"] for item in payload["files"]]
                touched_files.update(modified_files)
                missing_required = sorted(set(args.required_file) - touched_files)
                failures = [
                    "Required file has not been changed: " + path for path in missing_required
                ]
                failures += public_failures(workspace, args.acceptance) + build_failures(workspace)
                attempt_trace.update(
                    {
                        "modifiedFiles": modified_files,
                        "publicVerification": "passed" if not failures else "failed",
                        "failures": failures,
                    }
                )
                trace["toolCalls"].extend(
                    [
                        {"tool": "model.edit", "attempt": attempt + 1, "result": "completed"},
                        {"tool": "workspace.apply", "attempt": attempt + 1, "files": changed_count},
                        {
                            "tool": "maven.test",
                            "attempt": attempt + 1,
                            "result": "passed" if not failures else "failed",
                        },
                    ]
                )
            except (
                ValueError,
                OSError,
                json.JSONDecodeError,
                urllib.error.URLError,
            ) as error:
                failures = [f"Patch application failed: {error}"]
                attempt_trace.update({"modifiedFiles": [], "failures": failures})
                trace["toolCalls"].append(
                    {"tool": "model.edit", "attempt": attempt + 1, "result": "failed"}
                )
            trace["attempts"].append(attempt_trace)
            trace["selectedContext"] = selected_context
            trace["usage"] = usage
            write_trace(workspace, trace)
            if not failures or attempt == 2:
                break
            feedback = (
                "\n\nRETRY REQUIRED. Your previous patch failed public verification. Fix the current "
                "workspace and return complete replacement content again. Do not explain. Before "
                "returning, verify every public check yourself. A CONTAINS check requires its value "
                "to appear as exact literal source text; compilation errors must be fixed with all "
                "required imports and types. Preserve every pre-existing API used anywhere in the "
                "workspace and do not move a public top-level type into a differently named file. "
                "Failures:\n- "
                + "\n- ".join(failures)
            )
        output_tokens = usage["output_tokens"]
        reasoning_tokens = min(output_tokens, usage["reasoning_output_tokens"])
        event = {
            "type": "turn.completed",
            "usage": {
                "input_tokens": usage["input_tokens"],
                "cached_input_tokens": 0,
                "output_tokens": output_tokens,
                "reasoning_output_tokens": reasoning_tokens,
            },
        }
        trace["usage"] = usage
        trace["finalPublicVerification"] = "passed" if not failures else "failed"
        write_trace(workspace, trace)
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
