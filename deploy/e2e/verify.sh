#!/usr/bin/env bash
set -euo pipefail

verify_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
verify_repo_root="$(cd "${verify_script_dir}/../.." && pwd)"

cd "${verify_repo_root}"
./mvnw --batch-mode --no-transfer-progress verify
npm --prefix apps/web ci
npm --prefix apps/web run build
npx --prefix apps/web playwright install chromium --no-shell
npm --prefix apps/web run test:e2e

server_log="${verify_repo_root}/apps/web/output/playwright/server.log"
if rg -n "AuthorizationDeniedException|response (has already been|already) committed|Background execution command failed" "${server_log}"; then
  echo "E2E server log contains an asynchronous security, response, or command failure." >&2
  exit 1
fi
