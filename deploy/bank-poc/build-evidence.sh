#!/bin/sh
set -eu

require_digest() {
  variable_name=$1
  variable_value=$2
  if ! printf '%s\n' "$variable_value" | grep -Eq '^.+@sha256:[0-9a-fA-F]{64}$'; then
    echo "$variable_name must be an approved image pinned by a full sha256 digest" >&2
    exit 2
  fi
  if ! docker image inspect "$variable_value" >/dev/null 2>&1; then
    echo "$variable_name is not present in the offline Docker image store" >&2
    exit 2
  fi
}

if [ -z "${LEGACY_PILOT_MAVEN_IMAGE:-}" ]; then
  echo "Set the approved Maven image digest in LEGACY_PILOT_MAVEN_IMAGE" >&2
  exit 2
fi
if [ -z "${LEGACY_PILOT_POSTGRES_TEST_IMAGE:-}" ]; then
  echo "Set the approved PostgreSQL test image digest in LEGACY_PILOT_POSTGRES_TEST_IMAGE" >&2
  exit 2
fi
require_digest LEGACY_PILOT_MAVEN_IMAGE "$LEGACY_PILOT_MAVEN_IMAGE"
require_digest LEGACY_PILOT_POSTGRES_TEST_IMAGE "$LEGACY_PILOT_POSTGRES_TEST_IMAGE"

output_directory=${1:-target/bank-poc-evidence}
TESTCONTAINERS_RYUK_DISABLED=true ./mvnw --batch-mode --no-transfer-progress clean verify

require_passing_integration_report() {
  report_file=$1
  if [ ! -f "$report_file" ]; then
    echo "Required integration-test report is missing: $report_file" >&2
    exit 2
  fi
  report_summary=$(sed -n '2p' "$report_file")
  if ! printf '%s\n' "$report_summary" | grep -Eq 'tests="[1-9][0-9]*"'; then
    echo "Required integration tests did not execute: $report_file" >&2
    exit 2
  fi
  for zero_attribute in errors failures skipped; do
    if ! printf '%s\n' "$report_summary" | grep -q "$zero_attribute=\"0\""; then
      echo "Required integration tests failed or skipped: $report_file" >&2
      exit 2
    fi
  done
}

require_passing_integration_report \
  modules/sandbox-docker/target/surefire-reports/TEST-io.legacypilot.sandbox.DockerSandboxIntegrationTest.xml
require_passing_integration_report \
  apps/server/target/surefire-reports/TEST-io.legacypilot.server.PostgresLifecycleApiTest.xml

mkdir -p "$output_directory"
output_directory=$(cd "$output_directory" && pwd)
./mvnw --batch-mode --no-transfer-progress org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -DoutputFormat=json -DoutputName=bom
./mvnw --batch-mode --no-transfer-progress org.codehaus.mojo:license-maven-plugin:2.7.0:aggregate-add-third-party -Dlicense.thirdPartyFilename=THIRD-PARTY.txt
./mvnw --batch-mode --no-transfer-progress dependency:tree > "$output_directory/maven-dependencies.txt"
cp target/bom.json "$output_directory/bom.json"
cp target/generated-sources/license/THIRD-PARTY.txt "$output_directory/THIRD-PARTY.txt"
{
  git ls-files -z -- .mvn apps deploy docs evals modules samples README.md pom.xml mvnw
  find apps modules -path '*/target/*.jar' -type f -print0
} | sort -zu \
  | xargs -0 shasum -a 256 > "$output_directory/SHA256SUMS"
