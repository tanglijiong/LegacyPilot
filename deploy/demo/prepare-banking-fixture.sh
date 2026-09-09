#!/usr/bin/env bash
set -euo pipefail

fixture_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fixture_repo_root="$(cd "${fixture_script_dir}/../.." && pwd)"
fixture_target="${1:?用法：prepare-banking-fixture.sh <目标目录>}"
fixture_expected_revision="ee79b3282e22aaf419a137666a57a2b1b3f4f875"

if [ ! -d "${fixture_target}/.git" ]; then
  if [ -d "${fixture_target}" ] && [ -n "$(find "${fixture_target}" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    echo "Banking Demo 目标目录不是空目录或有效 Git 仓库：${fixture_target}" >&2
    exit 1
  fi
  mkdir -p "${fixture_target}"
  tar -C "${fixture_repo_root}/samples/banking-demo" \
    --exclude='./.git' --exclude='./target' -cf - . | tar -C "${fixture_target}" -xf -
  git -C "${fixture_target}" init -q -b main
  git -C "${fixture_target}" config user.name "LegacyPilot Demo"
  git -C "${fixture_target}" config user.email "demo@legacypilot.invalid"
  git -C "${fixture_target}" add .
  GIT_AUTHOR_DATE="2026-09-06T00:00:00Z" \
    GIT_COMMITTER_DATE="2026-09-06T00:00:00Z" \
    git -C "${fixture_target}" commit -q -m "Create immutable Banking Demo fixture"
fi

fixture_actual_revision="$(git -C "${fixture_target}" rev-parse HEAD)"
if [ "${fixture_actual_revision}" != "${fixture_expected_revision}" ]; then
  echo "Banking Demo revision 不匹配。" >&2
  echo "期望：${fixture_expected_revision}" >&2
  echo "实际：${fixture_actual_revision}" >&2
  echo "请保留或移动旧目录，并使用空目标目录重新准备候选演示。" >&2
  exit 1
fi

if [ -n "$(git -C "${fixture_target}" status --porcelain --untracked-files=all)" ]; then
  echo "Banking Demo 工作区不是干净状态，拒绝继续：${fixture_target}" >&2
  exit 1
fi

printf '%s\n' "${fixture_actual_revision}"
