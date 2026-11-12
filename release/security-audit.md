# Security audit

The record of the pre-release security audit, #48. The audit read the source, the workflows
and the shipped artifact and opened one issue per area that needed a change; each of those
issues adds its own section below when it closes, with what was found, the evidence (the
command and what it printed), what was done about it, and anything left open.

A setting is recorded with the value read and the date it was read, because a setting can
change without a commit. "Not read" means the token used could not see it; the owner fills it
in.

| Section | Issue |
| --- | --- |
| [Release pipeline](#release-pipeline-260) | #260 |

---

## Release pipeline (#260)

What stands between a pushed commit and a signed release on Play's production track: the
`release.yml` gate, the `release` environment, and the CI workflow it runs after. The job
behind the gate has the upload keystore and its passwords, the Play service account and a
`contents: write` token.

### Findings and what was done

| # | Finding | Resolution |
| --- | --- | --- |
| 1 | The gate trusted the *name* of the run that triggered it: any `v*` tag on any commit, a branch named like a version carrying its own workflow called `CI`, or a tag moved after CI passed, all reached `kind=release`. | Fixed: `.github/scripts/release-gate.sh` (below). |
| 2 | A newline in the manual run's `version` input wrote a second `kind=` line to `$GITHUB_OUTPUT` after `kind=dry-run`, turning a rehearsal into a production publish. | Fixed: the input must match `^[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?$`, `kind` is written last, and both publish steps also require `github.event_name == 'workflow_run'`. |
| 3 | The release job's `setup-gradle` could save the Gradle User Home, build cache included, from the release commit into `main`'s cache scope. | Fixed: `cache-read-only: true`. |
| 4 | The decoded keystore stayed in `$RUNNER_TEMP` for the rest of the job, `KEYSTORE_PATH` was exported to every later step, the checkout kept the job's `contents: write` token in `.git/config`, and the step summary echoed the dispatch input. | Fixed: removed by an `if: always()` step and written under `umask 077`; `KEYSTORE_PATH` is set on the build step only; `persist-credentials: false`; the summary is written by the gate from the validated version. |
| 5 | `titan-X.Y.Z.apk.sha256` proves integrity, not origin. | A *Signing certificate* section in `RELEASE.md` with the commands that print the fingerprint. The values are not published yet: the owner holds the keystore. |
| 6 | `ci.yml` declared no `permissions`, so pull requests (and Renovate's dependency updates) ran with the repository-default token, kept in `.git/config` by every checkout. | Fixed: `permissions: contents: read` at the top, `persist-credentials: false` on all three checkouts. |
| 7 | `GRADLE_ENCRYPTION_KEY` is shared by pull-request runs and the release job. It only encrypts configuration-cache entries, and the configuration cache is off (`org.gradle.configuration-cache` is set nowhere). | No change. Revisit if the configuration cache is turned on. |
| 8 | Pull requests and the keystore-holding job picked their runner from the same org variable, `CI_RUNNER`. | The gate and release jobs now run on `ubuntu-latest` unconditionally, so a self-hosted pool behind `CI_RUNNER` can never hold the keystore. |
| 9 | The `release` environment has no protection rules and no deployment-branch policy, and `main` is not protected (settings below). Any workflow on any branch that names `environment: release` gets its secrets; the gate only governs `release.yml`. Exposure is limited to people with write access: three collaborators, all admins; forking is disabled. | **Open — needs the owner.** Neither setting is available to a private repository on the organisation's current plan. See *Needs the owner*. |

### The gate: hardened `workflow_run`, not `push: tags`

The issue offered two designs. The gate stays on `workflow_run` and is hardened:

- **`workflow_run` runs `release.yml` as it is on `main`.** With `push: tags` the workflow
  file comes from the tagged commit, so a tag on a commit that edits `release.yml` would run
  without any gate at all; only a tag ruleset or a tags-only environment policy could stop
  it, and neither is available to this repository today (settings below). On `workflow_run`,
  the gate a run meets is always the reviewed one on `main`.
- **`push: tags` does not bind a tag to `main` either.** A tag ruleset decides who may push a
  `v*` tag, not where it points, so the ancestry check would be needed in both designs.
- **Least churn.** No `workflow_call` split of `ci.yml`, and no second CI run per tag (CI
  already runs on tags).

The cost of `workflow_run` is that GitHub sees its jobs as running on `main`, so environment
policies cannot tell a tag from a branch. The gate makes up for it. Before the `release`
environment sees the run, `.github/scripts/release-gate.sh`, checked out from `main` with
every tag and branch, requires:

1. `workflow_run.path` is `.github/workflows/ci.yml` and `head_repository` is this repository;
2. `head_branch` is `vX.Y.Z` or `vX.Y.Z-rc.N`, and no branch of that name exists;
3. `git rev-parse refs/tags/<name>^{commit}` equals `workflow_run.head_sha`;
4. `git merge-base --is-ancestor <head_sha> origin/main` succeeds.

A run whose name is a version and fails any check fails the gate (red, with the reason as an
annotation), rather than skipping. A manual run must give a version matching the pattern
above, and never publishes.

The release job also runs only for the three kinds the gate can write
(`contains(fromJSON('["candidate", "release", "dry-run"]'), …)`), not for anything other
than `none`.

#### Evidence

`make release-gate-check` (`.github/scripts/release-gate-test.sh`) builds a throwaway
repository under `$TMPDIR`, clones it the way the gate job's checkout does, and runs the gate
on each case. CI runs it in the *Seed, translations and release gate* job. On 2026-11-12:

```text
PASS  accepts an annotated tag on main
PASS  accepts a candidate tag on main
PASS  rejects a tag on an unmerged commit
PASS  rejects a v* branch carrying a workflow named CI
PASS  rejects a v* branch's CI run from another file
PASS  rejects a workflow named CI in another file, even on a good tag
PASS  rejects a tag moved after CI ran
PASS  rejects a name that is both a branch and a tag
PASS  rejects a run from another repository
PASS  rejects a tag that does not exist
PASS  does nothing for a ref that is not a version
PASS  accepts a dispatched version as a dry run
PASS  accepts a dispatched candidate version as a dry run
PASS  fails a dispatched version containing a newline
PASS  fails a dispatched version with a trailing space
PASS  fails an empty dispatched version
PASS  fails any other event
All cases passed
```

The same cases against the gate as it was before this change (the inline step extracted from
`release.yml`, run with `RELEASE_GATE=<file> make release-gate-check`) fail 12 of 17: every
rejection case reached `kind=release`, and the newline case wrote two `kind` lines,
`dry-run` then `release`.

### Settings read on 2026-11-12

Read with `gh api` GET requests and a fine-grained personal access token. Its account has the
admin role on the repository, but the token was not granted the repository's
*Administration*, *Secrets* or *Variables* permissions or any organisation permission, hence
the 403s. Nothing was changed or triggered.

| Setting | How it was read | Value on 2026-11-12 |
| --- | --- | --- |
| Visibility | `repos/open-gym-app/titan` | `private`, organisation-owned, `allow_forking: false` |
| Collaborators | `repos/open-gym-app/titan/collaborators` | three, all with the `admin` role |
| Default workflow token permissions | `repos/…/actions/permissions/workflow` | **Not read** (403) |
| Fork pull-request workflow settings | `repos/…/actions/permissions/fork-pr-workflows-private-repos` | **Not read** (403). Moot while forking is disabled. |
| Rulesets (tag and branch, `v*`) | `repos/…/rulesets` | **Unavailable**: 403 "Upgrade to GitHub Pro or make this repository public to enable this feature." So no ruleset applies. |
| `main` branch protection | `repos/…/branches/main` | `protected: false`, `protection.enabled: false`. The `/protection` endpoint itself returned 403. |
| `release` environment | `repos/…/environments/release` | created 2026-10-21; `protection_rules: []` (no required reviewers, no wait timer); `deployment_branch_policy: null` (any branch); `can_admins_bypass: true` |
| `release` environment secret names | `repos/…/environments/release/secrets` | **Not read** (403) |
| Org variable `CI_RUNNER` | `orgs/open-gym-app/actions/variables/CI_RUNNER` | **Not read** (403). Indirectly: every job of CI run 43027325904 (push to `main`, 2026-11-11) ran with labels `["ubuntu-latest"]` in runner group "GitHub Actions", so the variable is `ubuntu-latest` or unset. |
| Runner groups and their repository/workflow restrictions | `orgs/open-gym-app/actions/runner-groups` | **Not read** (403). Irrelevant to the release jobs now that they do not use `CI_RUNNER`. |
| Past Release runs | `gh run list --workflow release.yml --limit 100` | 26 runs, all `workflow_run` on `main`, all `skipped`; both jobs skipped with 0 steps. No `v*` tag exists (`repos/…/git/matching-refs/tags/v` is empty). |
| Did a Release run save a Gradle cache? | `repos/…/actions/caches` | No. The 49 entries are all on `refs/heads/main`, keyed by CI's jobs (`check`, `release-build`, and older job names); none is keyed `release`. No release job has ever run. |

### Needs the owner

- Read the four settings above marked **Not read**, with an admin token or in the web UI, and
  replace each cell with its value and the date.
- Restrict the `release` environment as soon as the plan allows it (making the repository
  public, or a paid plan): deployment branches *Selected branches and tags* → `main` only, and
  at least one required reviewer. `main` is the right policy even though tags are what
  release: a `workflow_run` job counts as running on `main`, so this admits `release.yml` and
  refuses a workflow on any other branch, and a manual run from one, that names the
  environment.
- Protect `main` (a ruleset or branch protection: pull request required, no force push), so
  that "on `main`" in the gate means "reviewed". Same plan constraint.
- Add a tag ruleset restricting who may create, move or delete `v*` tags, once available.
- Publish the two certificate fingerprints in `RELEASE.md` → *Signing certificate*.
- Run the scratch-repository acceptance run described in the pull request that closed #260
  and record its result here.
