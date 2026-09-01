#!/bin/sh
set -eu

require_value() {
  variable_name=$1
  variable_value=$2
  if [ -z "$variable_value" ]; then
    echo "Required configuration is missing: $variable_name" >&2
    exit 2
  fi
}

require_absolute_path() {
  variable_name=$1
  variable_value=$2
  case "$variable_value" in
    /*) ;;
    *)
      echo "$variable_name must be an absolute path" >&2
      exit 2
      ;;
  esac
}

require_digest_image() {
  variable_name=$1
  variable_value=$2
  if ! printf '%s\n' "$variable_value" | grep -Eq '^.+@sha256:[0-9a-fA-F]{64}$'; then
    echo "$variable_name must be pinned by a full sha256 digest" >&2
    exit 2
  fi
  if ! docker image inspect "$variable_value" >/dev/null 2>&1; then
    echo "$variable_name is not present in the offline Docker image store" >&2
    exit 2
  fi
}

require_value LEGACY_PILOT_SERVER_JAR "${LEGACY_PILOT_SERVER_JAR:-}"
require_value LEGACY_PILOT_POLICY_FILE "${LEGACY_PILOT_POLICY_FILE:-}"
require_value LEGACY_PILOT_MODEL_SOCKET "${LEGACY_PILOT_MODEL_SOCKET:-}"
require_value LEGACY_PILOT_WORK_ROOT "${LEGACY_PILOT_WORK_ROOT:-}"
require_value LEGACY_PILOT_AGENT_STATE_ROOT "${LEGACY_PILOT_AGENT_STATE_ROOT:-}"
require_value LEGACY_PILOT_OIDC_ISSUER "${LEGACY_PILOT_OIDC_ISSUER:-}"
require_value LEGACY_PILOT_DATABASE_URL "${LEGACY_PILOT_DATABASE_URL:-}"
require_value LEGACY_PILOT_MAVEN_IMAGE "${LEGACY_PILOT_MAVEN_IMAGE:-}"
require_value LEGACY_PILOT_MODEL_IMAGE "${LEGACY_PILOT_MODEL_IMAGE:-}"
require_value LEGACY_PILOT_MODEL_WEIGHTS "${LEGACY_PILOT_MODEL_WEIGHTS:-}"
require_value MODEL_ARTIFACT_SHA256 "${MODEL_ARTIFACT_SHA256:-}"
require_value POSTGRES_IMAGE "${POSTGRES_IMAGE:-}"
require_value POSTGRES_PASSWORD_FILE "${POSTGRES_PASSWORD_FILE:-}"

require_absolute_path LEGACY_PILOT_SERVER_JAR "$LEGACY_PILOT_SERVER_JAR"
require_absolute_path LEGACY_PILOT_POLICY_FILE "$LEGACY_PILOT_POLICY_FILE"
require_absolute_path LEGACY_PILOT_MODEL_SOCKET "$LEGACY_PILOT_MODEL_SOCKET"
require_absolute_path LEGACY_PILOT_WORK_ROOT "$LEGACY_PILOT_WORK_ROOT"
require_absolute_path LEGACY_PILOT_AGENT_STATE_ROOT "$LEGACY_PILOT_AGENT_STATE_ROOT"
require_absolute_path LEGACY_PILOT_MODEL_WEIGHTS "$LEGACY_PILOT_MODEL_WEIGHTS"
require_absolute_path POSTGRES_PASSWORD_FILE "$POSTGRES_PASSWORD_FILE"

if [ "${LEGACY_PILOT_SECURITY_MODE:-}" != oidc ]; then
  echo "LEGACY_PILOT_SECURITY_MODE must be oidc" >&2
  exit 2
fi
if [ "${LEGACY_PILOT_SANDBOX_DIGEST_REQUIRED:-}" != true ]; then
  echo "LEGACY_PILOT_SANDBOX_DIGEST_REQUIRED must be true" >&2
  exit 2
fi
case "$LEGACY_PILOT_OIDC_ISSUER" in
  https://*) ;;
  *)
    echo "LEGACY_PILOT_OIDC_ISSUER must use https" >&2
    exit 2
    ;;
esac
case "$LEGACY_PILOT_DATABASE_URL" in
  jdbc:postgresql://*) ;;
  *)
    echo "LEGACY_PILOT_DATABASE_URL must use jdbc:postgresql" >&2
    exit 2
    ;;
esac
if ! printf '%s\n' "$MODEL_ARTIFACT_SHA256" | grep -Eq '^[0-9a-fA-F]{64}$'; then
  echo "MODEL_ARTIFACT_SHA256 must be a full sha256 digest" >&2
  exit 2
fi

require_digest_image LEGACY_PILOT_MAVEN_IMAGE "$LEGACY_PILOT_MAVEN_IMAGE"
require_digest_image LEGACY_PILOT_MODEL_IMAGE "$LEGACY_PILOT_MODEL_IMAGE"
require_digest_image POSTGRES_IMAGE "$POSTGRES_IMAGE"

if [ ! -f "$LEGACY_PILOT_SERVER_JAR" ] || [ -L "$LEGACY_PILOT_SERVER_JAR" ]; then
  echo "LEGACY_PILOT_SERVER_JAR must be a regular non-symlink file" >&2
  exit 2
fi
if [ ! -f "$LEGACY_PILOT_POLICY_FILE" ] || [ -L "$LEGACY_PILOT_POLICY_FILE" ]; then
  echo "LEGACY_PILOT_POLICY_FILE must be a regular non-symlink file" >&2
  exit 2
fi
if [ ! -f "$POSTGRES_PASSWORD_FILE" ] || [ -L "$POSTGRES_PASSWORD_FILE" ]; then
  echo "POSTGRES_PASSWORD_FILE must be a regular non-symlink file" >&2
  exit 2
fi
if [ ! -d "$LEGACY_PILOT_MODEL_WEIGHTS" ] || [ -L "$LEGACY_PILOT_MODEL_WEIGHTS" ]; then
  echo "LEGACY_PILOT_MODEL_WEIGHTS must be a non-symlink directory" >&2
  exit 2
fi
if [ -w "$LEGACY_PILOT_MODEL_WEIGHTS" ]; then
  echo "LEGACY_PILOT_MODEL_WEIGHTS must be read-only for the LegacyPilot account" >&2
  exit 2
fi
model_digest_marker=$LEGACY_PILOT_MODEL_WEIGHTS/MODEL_ARTIFACT_SHA256
if [ ! -f "$model_digest_marker" ] || [ -L "$model_digest_marker" ]; then
  echo "The approved model digest marker is missing or is a symlink" >&2
  exit 2
fi
if [ "$(tr -d '[:space:]' < "$model_digest_marker")" != "$MODEL_ARTIFACT_SHA256" ]; then
  echo "MODEL_ARTIFACT_SHA256 does not match the approved weights marker" >&2
  exit 2
fi
if [ ! -S "$LEGACY_PILOT_MODEL_SOCKET" ] || [ -L "$LEGACY_PILOT_MODEL_SOCKET" ]; then
  echo "LEGACY_PILOT_MODEL_SOCKET must be a Unix socket and not a symlink" >&2
  exit 2
fi
for writable_directory in "$LEGACY_PILOT_WORK_ROOT" "$LEGACY_PILOT_AGENT_STATE_ROOT"; do
  if [ ! -d "$writable_directory" ] || [ ! -w "$writable_directory" ]; then
    echo "Required directory is missing or not writable: $writable_directory" >&2
    exit 2
  fi
done

if [ "$(id -u)" -eq 0 ]; then
  echo "LegacyPilot must run as a dedicated non-root account" >&2
  exit 2
fi
java -version
git --version
docker info --format '{{.ServerVersion}}'
echo "LegacyPilot bank PoC preflight passed"
