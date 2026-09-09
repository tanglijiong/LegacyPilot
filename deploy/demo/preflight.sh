#!/usr/bin/env bash
set -euo pipefail

demo_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
demo_repo_root="$(cd "${demo_script_dir}/../.." && pwd)"
demo_model_url="${LEGACY_PILOT_MODEL_BASE_URL:-http://127.0.0.1:12434/engines/v1}"

for demo_command in git java node npm curl tar docker; do
  if ! command -v "${demo_command}" >/dev/null 2>&1; then
    echo "缺少必需命令：${demo_command}" >&2
    exit 1
  fi
done

if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon 不可用；CHANGE 的编译、测试和静态检查需要离线 Maven sandbox。" >&2
  echo "请启动 Docker 后重试。" >&2
  exit 1
fi

demo_java_version="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')"
if [ -z "${demo_java_version}" ] || [ "${demo_java_version}" -lt 21 ]; then
  echo "需要 JDK 21 或更高版本，当前 java 未满足要求。" >&2
  exit 1
fi

if [ ! -d "${demo_repo_root}/samples/banking-demo" ]; then
  echo "找不到 samples/banking-demo，请从 LegacyPilot 仓库根目录运行。" >&2
  exit 1
fi

if [ "${LEGACY_PILOT_DEMO_SKIP_MODEL_CHECK:-false}" != "true" ]; then
  if ! curl --fail --silent --show-error --max-time 5 "${demo_model_url%/}/models" >/dev/null; then
    echo "本地模型不可用：${demo_model_url%/}/models" >&2
    echo "请先按 deploy/deepseek/README.md 启动模型；只检查界面时可设置 LEGACY_PILOT_DEMO_SKIP_MODEL_CHECK=true。" >&2
    exit 1
  fi
fi

echo "启动检查通过：JDK、Node.js、Git、Banking Demo 和模型配置均可用。"
