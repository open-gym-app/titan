#!/usr/bin/env bash
# The release gate: decides what a run of release.yml is, and refuses a run whose commit is
# not the one a release tag on `main` names. It is what binds the signing secrets to a tag on
# `main`: the `release` environment cannot, because a deployment-branch policy sees every
# workflow_run job as running on the default branch. So it trusts nothing about the
# triggering run but its commit, and checks that against the repository's own refs.
#
# Run by the `gate` job with the event's fields in the environment, inside a clone that has
# every tag and `origin/main`:
#
#   EVENT            github.event_name: workflow_dispatch or workflow_run
#   INPUT_VERSION    the dispatch's `version` input
#   HEAD_BRANCH      workflow_run.head_branch: the tag's name for a tag push, a branch's otherwise
#   HEAD_SHA         workflow_run.head_sha: the commit CI ran on
#   WORKFLOW_PATH    workflow_run.path: the file the triggering workflow was read from
#   HEAD_REPOSITORY  workflow_run.head_repository.full_name
#   REPOSITORY       github.repository
#   MAIN_REF         the ref `main` is fetched as (default refs/remotes/origin/main)
#
# Writes `version` and then `kind` (dry-run, candidate, release or none) to $GITHUB_OUTPUT,
# `kind` last, so nothing written before it can add a second `kind` line that wins. Exits
# non-zero, writing no `kind`, on anything that names a version and fails a check.
#
# .github/scripts/release-gate-test.sh runs it against a throwaway git repository.
set -euo pipefail

# The same shape app/build.gradle.kts accepts for -PreleaseVersion.
readonly VERSION_PATTERN='^[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?$'
# The workflow `workflows: [CI]` means. Any file can call itself CI, on any branch.
readonly CI_WORKFLOW_PATH='.github/workflows/ci.yml'
MAIN_REF="${MAIN_REF:-refs/remotes/origin/main}"

# Writes a workflow command. Its message names refs and paths from the triggering run, so it
# is escaped the way GitHub decodes command data: a newline in one could otherwise end the
# command and start another.
annotate() {
  local level="$1" message="$2"
  message="${message//%/%25}"
  message="${message//$'\r'/%0D}"
  message="${message//$'\n'/%0A}"
  printf '::%s::%s\n' "$level" "$message"
}

fail() {
  annotate error "$1"
  exit 1
}

emit() {
  local kind="$1" version="$2"
  printf 'version=%s\n' "$version" >>"$GITHUB_OUTPUT"
  printf 'kind=%s\n' "$kind" >>"$GITHUB_OUTPUT"
  # Only a version that matched VERSION_PATTERN gets here, so it needs no escaping.
  # shellcheck disable=SC2016 # the backticks are a Markdown code span
  if [[ "$kind" != none ]]; then
    printf 'Releasing `%s` (%s)\n' "$version" "$kind" >>"${GITHUB_STEP_SUMMARY:-/dev/null}"
  fi
}

case "${EVENT:-}" in
  workflow_dispatch)
    # Not echoed: a dispatch input is free text, newlines and workflow commands included.
    [[ "${INPUT_VERSION:-}" =~ $VERSION_PATTERN ]] ||
      fail "The version input is not MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-rc.N"
    emit dry-run "$INPUT_VERSION"
    ;;

  workflow_run)
    ref="${HEAD_BRANCH:-}"
    version="${ref#v}"
    if [[ "$ref" != v* || ! "$version" =~ $VERSION_PATTERN ]]; then
      annotate notice "$ref is not a release tag, nothing to do"
      emit none ""
      exit 0
    fi
    if [[ "$version" == *-rc.* ]]; then kind=candidate; else kind=release; fi

    # From here on the run names a version, so every check that fails fails the gate rather
    # than skipping: a red Release run is the signal that someone tried.
    [[ "${WORKFLOW_PATH:-}" == "$CI_WORKFLOW_PATH" ]] ||
      fail "$ref: triggered by ${WORKFLOW_PATH:-an unknown file}, not $CI_WORKFLOW_PATH"
    [[ -n "${REPOSITORY:-}" && "${HEAD_REPOSITORY:-}" == "$REPOSITORY" ]] ||
      fail "$ref: CI ran on ${HEAD_REPOSITORY:-an unknown repository}, not ${REPOSITORY:-this one}"
    [[ "${HEAD_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || fail "$ref: CI's head_sha is not a commit id"

    # `head_branch` is a bare name: a branch called v1.2.3 looks exactly like the tag. A
    # branch's push runs that branch's own workflows, so the name alone proves nothing.
    if git show-ref --verify --quiet "refs/remotes/origin/$ref"; then
      fail "$ref is a branch as well as a tag name; delete the branch and push the tag again"
    fi
    tagged=$(git rev-parse --verify --quiet "refs/tags/$ref^{commit}") ||
      fail "$ref: there is no such tag; a branch named like a version is not a release"
    # Also catches a tag moved after CI ran on its old commit.
    [[ "$tagged" == "$HEAD_SHA" ]] ||
      fail "$ref: the tag points at $tagged, but CI ran on $HEAD_SHA"
    git merge-base --is-ancestor "$HEAD_SHA" "$MAIN_REF" ||
      fail "$ref: $HEAD_SHA is not on main; tag a commit that has been merged"

    emit "$kind" "$version"
    ;;

  *)
    fail "Unexpected event '${EVENT:-}'"
    ;;
esac
