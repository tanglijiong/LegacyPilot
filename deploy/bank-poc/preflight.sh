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

require_value LEGACY_PILOT_SERVER_JAR "${LEGACY_PILOT_SERVER_JAR:-}"
require_value LEGACY_PILOT_POLICY_FILE "${LEGACY_PILOT_POLICY_FILE:-}"
require_value LEGACY_PILOT_MODEL_SOCKET "${LEGACY_PILOT_MODEL_SOCKET:-}"
require_value LEGACY_PILOT_WORK_ROOT "${LEGACY_PILOT_WORK_ROOT:-}"
require_value LEGACY_PILOT_AGENT_STATE_ROOT "${LEGACY_PILOT_AGENT_STATE_ROOT:-}"
require_value LEGACY_PILOT_OIDC_ISSUER "${LEGACY_PILOT_OIDC_ISSUER:-}"
require_value LEGACY_PILOT_DATABASE_URL "${LEGACY_PILOT_DATABASE_URL:-}"
require_value LEGACY_PILOT_MAVEN_IMAGE "${LEGACY_PILOT_MAVEN_IMAGE:-}"

require_absolute_path LEGACY_PILOT_SERVER_JAR "$LEGACY_PILOT_SERVER_JAR"
require_absolute_path LEGACY_PILOT_POLICY_FILE "$LEGACY_PILOT_POLICY_FILE"
require_absolute_path LEGACY_PILOT_MODEL_SOCKET "$LEGACY_PILOT_MODEL_SOCKET"
require_absolute_path LEGACY_PILOT_WORK_ROOT "$LEGACY_PILOT_WORK_ROOT"
require_absolute_path LEGACY_PILOT_AGENT_STATE_ROOT "$LEGACY_PILOT_AGENT_STATE_ROOT"

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
if ! printf '%s\n' "$LEGACY_PILOT_MAVEN_IMAGE" \
  | grep -Eq '^.+@sha256:[0-9a-fA-F]{64}$'; then
  echo "LEGACY_PILOT_MAVEN_IMAGE must be pinned by a full sha256 digest" >&2
  exit 2
fi

if [ ! -f "$LEGACY_PILOT_SERVER_JAR" ] || [ -L "$LEGACY_PILOT_SERVER_JAR" ]; then
  echo "LEGACY_PILOT_SERVER_JAR must be a regular non-symlink file" >&2
  exit 2
fi
if [ ! -f "$LEGACY_PILOT_POLICY_FILE" ] || [ -L "$LEGACY_PILOT_POLICY_FILE" ]; then
  echo "LEGACY_PILOT_POLICY_FILE must be a regular non-symlink file" >&2
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
