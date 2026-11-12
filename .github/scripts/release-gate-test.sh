#!/usr/bin/env bash
# Runs release-gate.sh against a throwaway git repository, one case per way a run could
# reach the signing secrets without being a tag on `main`, and the two ways it should.
# `make release-gate-check` runs it, and so does CI.
#
# The fixture is cloned the way the gate job's checkout clones (every branch under
# refs/remotes/origin, every tag), so the gate sees the refs it sees on a runner:
#
#   c1 ── c2 ── c3          main
#          │     └── v4.0.0 branch and tag of the same name
#          ├── f1           feature, unmerged
#          └── b1           branch v9.9.9, carrying its own workflow named CI
set -euo pipefail

# RELEASE_GATE points the cases at another copy of the gate, to see which of them it fails.
gate="${RELEASE_GATE:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release-gate.sh}"
work="$(mktemp -d "${TMPDIR:-/tmp}/release-gate.XXXXXX")"
trap 'rm -rf "$work"' EXIT

# The developer's git config must not leak in: commit or tag signing would stop the fixture.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=fixture GIT_AUTHOR_EMAIL=fixture@example.invalid
export GIT_COMMITTER_NAME=fixture GIT_COMMITTER_EMAIL=fixture@example.invalid

upstream="$work/upstream"
git init -q -b main "$upstream"
cd "$upstream"
commit() { echo "$1" >"$1.txt" && git add "$1.txt" && git commit -q -m "$1" && git rev-parse HEAD; }

c1=$(commit c1)
c2=$(commit c2)
git switch -q -c feature
f1=$(commit f1)
git switch -q -c v9.9.9 main
mkdir -p .github/workflows
printf 'name: CI\non: push\n' >.github/workflows/ci.yml
b1=$(commit b1)
git switch -q main
c3=$(commit c3)

git tag -a -m v1.0.0 v1.0.0 "$c2"   # annotated, on main
git tag v1.0.1-rc.1 "$c1"           # lightweight, on main
git tag -a -m v2.0.0 v2.0.0 "$f1"   # annotated, unmerged
git tag v3.0.0 "$c1"                # CI runs on c1, then the tag is moved to c3
git tag -f v3.0.0 "$c3" >/dev/null
git branch v4.0.0 "$c3"             # a branch and a tag of the same name
git tag v4.0.0 "$c3"

clone="$work/clone"
git clone -q --no-checkout "$upstream" "$clone"
cd "$clone"

failures=0
# run NAME EXPECTED_EXIT EXPECTED_KIND [VAR=value ...]
run() {
  local name="$1" want_exit="$2" want_kind="$3"
  shift 3
  local out="$work/output" log="$work/log" got_exit=0
  : >"$out"
  env -i PATH="$PATH" HOME="$work" GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1 \
    GITHUB_OUTPUT="$out" REPOSITORY=open-gym-app/titan \
    HEAD_REPOSITORY=open-gym-app/titan WORKFLOW_PATH=.github/workflows/ci.yml \
    "$@" bash "$gate" >"$log" 2>&1 || got_exit=$?

  local kinds got_kind
  kinds=$(grep -c '^kind=' "$out" || true)
  got_kind=$(sed -n 's/^kind=//p' "$out")
  if [[ "$got_exit" == "$want_exit" && "$got_kind" == "$want_kind" && "$kinds" -le 1 ]]; then
    printf 'PASS  %s\n' "$name"
  else
    printf 'FAIL  %s: exit %s (want %s), kind "%s" (want "%s"), %s kind lines\n' \
      "$name" "$got_exit" "$want_exit" "$got_kind" "$want_kind" "$kinds"
    sed 's/^/      /' "$log"
    failures=$((failures + 1))
  fi
}

run "accepts an annotated tag on main" 0 release \
  EVENT=workflow_run HEAD_BRANCH=v1.0.0 HEAD_SHA="$c2"
run "accepts a candidate tag on main" 0 candidate \
  EVENT=workflow_run HEAD_BRANCH=v1.0.1-rc.1 HEAD_SHA="$c1"
run "rejects a tag on an unmerged commit" 1 "" \
  EVENT=workflow_run HEAD_BRANCH=v2.0.0 HEAD_SHA="$f1"
run "rejects a v* branch carrying a workflow named CI" 1 "" \
  EVENT=workflow_run HEAD_BRANCH=v9.9.9 HEAD_SHA="$b1"
run "rejects a v* branch's CI run from another file" 1 "" \
  EVENT=workflow_run HEAD_BRANCH=v9.9.9 HEAD_SHA="$b1" WORKFLOW_PATH=.github/workflows/evil.yml
run "rejects a workflow named CI in another file, even on a good tag" 1 "" \
  EVENT=workflow_run HEAD_BRANCH=v1.0.0 HEAD_SHA="$c2" WORKFLOW_PATH=.github/workflows/evil.yml
run "rejects a tag moved after CI ran" 1 "" \
  EVENT=workflow_run HEAD_BRANCH=v3.0.0 HEAD_SHA="$c1"
run "rejects a name that is both a branch and a tag" 1 "" \
  EVENT=workflow_run HEAD_BRANCH=v4.0.0 HEAD_SHA="$c3"
run "rejects a run from another repository" 1 "" \
  EVENT=workflow_run HEAD_BRANCH=v1.0.0 HEAD_SHA="$c2" HEAD_REPOSITORY=someone/titan
run "rejects a tag that does not exist" 1 "" \
  EVENT=workflow_run HEAD_BRANCH=v7.0.0 HEAD_SHA="$c2"
run "does nothing for a ref that is not a version" 0 none \
  EVENT=workflow_run HEAD_BRANCH=vendor-bump HEAD_SHA="$c2"
run "accepts a dispatched version as a dry run" 0 dry-run \
  EVENT=workflow_dispatch INPUT_VERSION=1.2.3
run "accepts a dispatched candidate version as a dry run" 0 dry-run \
  EVENT=workflow_dispatch INPUT_VERSION=1.2.3-rc.4
run "fails a dispatched version containing a newline" 1 "" \
  EVENT=workflow_dispatch INPUT_VERSION=$'1.2.3\nkind=release'
run "fails a dispatched version with a trailing space" 1 "" \
  EVENT=workflow_dispatch "INPUT_VERSION=1.2.3 "
run "fails an empty dispatched version" 1 "" \
  EVENT=workflow_dispatch INPUT_VERSION=
run "fails any other event" 1 "" \
  EVENT=push HEAD_BRANCH=v1.0.0 HEAD_SHA="$c2"

if ((failures > 0)); then
  echo "$failures case(s) failed"
  exit 1
fi
echo "All cases passed"
