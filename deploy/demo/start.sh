#!/usr/bin/env bash
set -euo pipefail

demo_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
demo_repo_root="$(cd "${demo_script_dir}/../.." && pwd)"
demo_runtime_root="${LEGACY_PILOT_DEMO_ROOT:-${demo_repo_root}/.legacy-pilot-demo}"
demo_source="${demo_runtime_root}/banking-demo"
demo_logs="${demo_runtime_root}/logs"
demo_model_url="${LEGACY_PILOT_MODEL_BASE_URL:-http://127.0.0.1:12434/engines/v1}"
demo_model="${LEGACY_PILOT_MODEL:-hf.co/bartowski/deepseek-coder-v2-lite-instruct-gguf:q4_k_m}"
demo_server_port="${LEGACY_PILOT_DEMO_SERVER_PORT:-8080}"
demo_web_port="${LEGACY_PILOT_DEMO_WEB_PORT:-5173}"
demo_context_token_budget="${LEGACY_PILOT_CONTEXT_TOKEN_BUDGET:-650}"
demo_maximum_steps="${LEGACY_PILOT_MAXIMUM_STEPS:-100}"
demo_maximum_retries="${LEGACY_PILOT_MAXIMUM_RETRIES:-50}"
demo_maximum_identical_failures="${LEGACY_PILOT_MAXIMUM_IDENTICAL_FAILURES:-50}"
demo_model_maximum_corrections="${LEGACY_PILOT_MODEL_MAXIMUM_CORRECTIONS:-10}"

mkdir -p "${demo_runtime_root}" "${demo_logs}"

demo_revision="$("${demo_script_dir}/prepare-banking-fixture.sh" "${demo_source}")"

LEGACY_PILOT_MODEL_BASE_URL="${demo_model_url}" "${demo_script_dir}/preflight.sh"

if command -v lsof >/dev/null 2>&1; then
  if lsof -nP -iTCP:"${demo_server_port}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "端口 ${demo_server_port} 已被占用，请先停止对应服务或设置 LEGACY_PILOT_DEMO_SERVER_PORT。" >&2
    exit 1
  fi
  if lsof -nP -iTCP:"${demo_web_port}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "端口 ${demo_web_port} 已被占用，请先停止对应服务或设置 LEGACY_PILOT_DEMO_WEB_PORT。" >&2
    exit 1
  fi
fi

cd "${demo_repo_root}"
./mvnw -q -pl apps/server -am package -DskipTests

if [ ! -d "${demo_repo_root}/apps/web/node_modules" ]; then
  npm --prefix "${demo_repo_root}/apps/web" install
fi

demo_server_jar="${demo_repo_root}/apps/server/target/legacy-pilot-server-0.3.0-SNAPSHOT.jar"
SERVER_PORT="${demo_server_port}" \
SPRING_PROFILES_ACTIVE=demo \
LEGACY_PILOT_DEMO_SOURCE="${demo_source}" \
LEGACY_PILOT_DEMO_REVISION="${demo_revision}" \
LEGACY_PILOT_DATA_DIR="${demo_runtime_root}/data" \
LEGACY_PILOT_WORK_ROOT="${demo_runtime_root}/work" \
LEGACY_PILOT_AGENT_STATE_ROOT="${demo_runtime_root}/agent" \
LEGACY_PILOT_CONTEXT_TOKEN_BUDGET="${demo_context_token_budget}" \
LEGACY_PILOT_MAXIMUM_STEPS="${demo_maximum_steps}" \
LEGACY_PILOT_MAXIMUM_RETRIES="${demo_maximum_retries}" \
LEGACY_PILOT_MODEL_MAXIMUM_CORRECTIONS="${demo_model_maximum_corrections}" \
LEGACY_PILOT_MODEL_BASE_URL="${demo_model_url}" \
LEGACY_PILOT_MODEL="${demo_model}" \
java -Dlegacypilot.maximum-identical-failures="${demo_maximum_identical_failures}" \
  -jar "${demo_server_jar}" >"${demo_logs}/server.log" 2>&1 &
demo_server_pid=$!

demo_web_pid=""
cleanup_demo() {
  if [ -n "${demo_web_pid}" ]; then
    kill "${demo_web_pid}" >/dev/null 2>&1 || true
  fi
  kill "${demo_server_pid}" >/dev/null 2>&1 || true
}
trap cleanup_demo EXIT INT TERM

demo_attempt=0
until curl --fail --silent --max-time 2 "http://127.0.0.1:${demo_server_port}/api/v1/demo/session" >/dev/null; do
  demo_attempt=$((demo_attempt + 1))
  if ! kill -0 "${demo_server_pid}" >/dev/null 2>&1; then
    echo "服务端启动失败，日志：${demo_logs}/server.log" >&2
    exit 1
  fi
  if [ "${demo_attempt}" -ge 60 ]; then
    echo "服务端启动超时，日志：${demo_logs}/server.log" >&2
    exit 1
  fi
  sleep 1
done

VITE_API_PROXY_TARGET="http://127.0.0.1:${demo_server_port}" \
  npm --prefix "${demo_repo_root}/apps/web" run dev -- --port "${demo_web_port}" >"${demo_logs}/web.log" 2>&1 &
demo_web_pid=$!

demo_attempt=0
until curl --fail --silent --max-time 2 "http://127.0.0.1:${demo_web_port}" >/dev/null; do
  demo_attempt=$((demo_attempt + 1))
  if ! kill -0 "${demo_web_pid}" >/dev/null 2>&1; then
    echo "Web 启动失败，日志：${demo_logs}/web.log" >&2
    exit 1
  fi
  if [ "${demo_attempt}" -ge 30 ]; then
    echo "Web 启动超时，日志：${demo_logs}/web.log" >&2
    exit 1
  fi
  sleep 1
done

echo "LegacyPilot Demo 已启动：http://127.0.0.1:${demo_web_port}"
echo "Banking Demo revision：${demo_revision}"
echo "工作台会自动取得本次进程的临时开发者和审批人身份。"
echo "运行日志：${demo_logs}"
echo "按 Ctrl-C 停止服务；任务数据会保留供下次启动恢复。"

while kill -0 "${demo_server_pid}" >/dev/null 2>&1 && kill -0 "${demo_web_pid}" >/dev/null 2>&1; do
  sleep 2
done

echo "演示服务意外停止，请检查 ${demo_logs}。" >&2
exit 1
