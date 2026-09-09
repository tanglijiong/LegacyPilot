#!/usr/bin/env bash
set -euo pipefail

e2e_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
e2e_repo_root="$(cd "${e2e_script_dir}/../.." && pwd)"
e2e_runtime_root="${LEGACY_PILOT_E2E_RUNTIME_ROOT:-}"
if [ -z "${e2e_runtime_root}" ]; then
  e2e_runtime_root="$(mktemp -d "${TMPDIR:-/tmp}/legacy-pilot-e2e.XXXXXX")"
else
  mkdir -p "${e2e_runtime_root}"
fi
e2e_source="${e2e_runtime_root}/banking-demo"
e2e_output="${e2e_repo_root}/apps/web/output/playwright"

mkdir -p "${e2e_source}" "${e2e_output}"
e2e_revision="$("${e2e_repo_root}/deploy/demo/prepare-banking-fixture.sh" "${e2e_source}")"

e2e_server_jar="${e2e_repo_root}/apps/server/target/legacy-pilot-server-0.3.0-SNAPSHOT.jar"
if [ ! -f "${e2e_server_jar}" ]; then
  "${e2e_repo_root}/mvnw" -q -f "${e2e_repo_root}/pom.xml" -pl apps/server -am package -DskipTests
fi

exec env \
  SERVER_PORT="18080" \
  SPRING_PROFILES_ACTIVE=demo \
  LEGACY_PILOT_DEMO_SOURCE="${e2e_source}" \
  LEGACY_PILOT_DEMO_REVISION="${e2e_revision}" \
  LEGACY_PILOT_DATA_DIR="${e2e_runtime_root}/data" \
  LEGACY_PILOT_WORK_ROOT="${e2e_runtime_root}/work" \
  LEGACY_PILOT_AGENT_STATE_ROOT="${e2e_runtime_root}/agent" \
  LEGACY_PILOT_AGENT_LEASE_TTL="${LEGACY_PILOT_AGENT_LEASE_TTL:-2m}" \
  LEGACY_PILOT_COMMAND_LEASE_TTL="${LEGACY_PILOT_COMMAND_LEASE_TTL:-2m}" \
  LEGACY_PILOT_QUEUE_SCAN_INTERVAL="${LEGACY_PILOT_QUEUE_SCAN_INTERVAL:-5s}" \
  LEGACY_PILOT_MAXIMUM_RETRIES="${LEGACY_PILOT_MAXIMUM_RETRIES:-2}" \
  LEGACY_PILOT_MODEL_BASE_URL="http://127.0.0.1:12435/v1" \
  LOGGING_FILE_NAME="${e2e_output}/server.log" \
  java -jar "${e2e_server_jar}"
