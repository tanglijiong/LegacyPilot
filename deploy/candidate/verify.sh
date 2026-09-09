#!/usr/bin/env bash
set -euo pipefail

candidate_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
candidate_repo_root="$(cd "${candidate_script_dir}/../.." && pwd)"
candidate_manifest="${candidate_script_dir}/manifest.json"
candidate_expected_revision="ee79b3282e22aaf419a137666a57a2b1b3f4f875"
candidate_fixture="$(mktemp -d "${TMPDIR:-/tmp}/legacy-pilot-candidate.XXXXXX")"

cleanup_candidate() {
  case "${candidate_fixture}" in
    */legacy-pilot-candidate.*) rm -rf -- "${candidate_fixture}" ;;
    *) echo "拒绝清理非候选临时目录：${candidate_fixture}" >&2 ;;
  esac
}
trap cleanup_candidate EXIT

cd "${candidate_repo_root}"

if ! docker info >/dev/null 2>&1; then
  echo "候选 CHANGE 演练需要可用的 Docker daemon，以运行真实 Maven 验证。" >&2
  exit 1
fi

node -e '
  const manifest = require(process.argv[1]);
  if (manifest.decision !== "PRODUCT_DEMO_ACCEPTED_MODEL_QUALITY_NOT_MET") throw new Error("candidate decision is unsafe");
  if (manifest.qualityGate.passed || manifest.qualityGate.score !== 9 || manifest.qualityGate.required !== 14) throw new Error("quality gate must remain explicitly unmet");
' "${candidate_manifest}"

candidate_actual_revision="$(./deploy/demo/prepare-banking-fixture.sh "${candidate_fixture}/banking-demo")"
if [ "${candidate_actual_revision}" != "${candidate_expected_revision}" ]; then
  echo "候选 Banking Demo revision 漂移：${candidate_actual_revision}" >&2
  exit 1
fi

candidate_public_files=(
  README.md
  docs/INTERVIEW_DEMO.md
  docs/RELEASE_CANDIDATE_V0.3.md
  deploy/candidate/README.md
  deploy/candidate/manifest.json
)
if rg -n '(/Users/[^[:space:]`)]+|[A-Za-z]:\\Users\\|Bearer[[:space:]]+eyJ|sk-[A-Za-z0-9]{12,})' "${candidate_public_files[@]}"; then
  echo "候选公开材料包含绝对个人路径或疑似凭据。" >&2
  exit 1
fi

./deploy/e2e/verify.sh
cp apps/web/output/playwright/results.json apps/web/output/playwright/release-gate-results.json

npm --prefix apps/web run test:e2e -- \
  e2e/interview-demo.spec.ts \
  --repeat-each=3
cp apps/web/output/playwright/results.json apps/web/output/playwright/interview-rehearsal-results.json

echo "候选发布检查通过。"
echo "Banking Demo revision：${candidate_actual_revision}"
echo "产品演示门禁：通过；三次面试演练：通过。"
echo "20 题模型质量：9/20，低于 14/20 门槛，候选版本不得宣称模型质量发布通过。"
