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
| [Delete all data and restore: what they left behind](#delete-all-data-and-restore-what-they-left-behind-258) | #258 |
| [Backups in shared storage on Android 8 and 9](#backups-in-shared-storage-on-android-8-and-9-259) | #259 |

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
on each case. CI runs it in the *Seed, translations and release gate* job. On 2026-11-14:

```text
PASS  accepts an annotated tag on main
PASS  accepts a candidate tag on main
PASS  rejects a tag on an unmerged commit
PASS  rejects a v* branch carrying a workflow named CI
PASS  rejects a v* branch's CI run from another file
PASS  rejects a workflow named CI in another file, even on a good tag
PASS  rejects a tag moved after CI ran
PASS  rejects a name that is both a branch and a tag
PASS  rejects a workflow path with a newline, in a single annotation
PASS  rejects a run from another repository
PASS  rejects a head_sha that is not a full commit id
PASS  rejects a run when the repository is unknown
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
`release.yml`, run with `RELEASE_GATE=<file> make release-gate-check`) fail 15 of 20: every
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

---

## Delete all data and restore: what they left behind (#258)

"Delete all data" promises to leave the phone as a fresh install. A restore replaces every
table. Both left two kinds of state alone: the backup files, and what the operating system holds
for the workout that was running. The audit found these by reading the source. #258 reproduced
them in Robolectric tests before changing anything.

### Findings and what was done

| # | Finding | Resolution |
| --- | --- | --- |
| 1 | Every manual export, automatic backup and `*.json.tmp` in `backups/` survived "Delete all data". Each is the whole history in plain JSON. | Fixed: `LocalDataRepositoryImpl.wipe` empties both directories `AutoBackupFiles.everyDirectory` names, the external one and the internal fallback, and throws if a file will not go. The dialog and the auto-backup sentence now say so, in four languages. |
| 2 | "Delete all data" and a restore left the rest's note and exact alarm and the idle nudge's note and alarm. The rest alarm then posted "Rest over" for the erased workout, with a link to its id. | Fixed: both call `BackgroundRestTimer.set(null)` and `IdleSessionMonitor.disarm()` right after the session state goes to `Idle`. |
| 3 | `RestTimerBroadcasts` rebuilt a rest from the intent with no session check, so an expiry already on its way still posted. A restore can write another workout back under the same id. | Fixed: the expiry and "Skip rest" carry the session's `created_at` (`SessionRef`). An expiry is announced only when the draft or the in-progress row is that session; one for another session, like a skip, only takes the note down. Both handlers ask under `SessionLifecycleLock`, which delete all and a restore hold from the rest's teardown to the cleared rows, so one delivered mid-wipe waits and then finds nothing open. |
| 4 | `clearAllTables` keeps `sqlite_sequence`. An id near `Long.MAX_VALUE` already stored made every later insert fail with `SQLITE_FULL`, even after "Delete all data". | Fixed: the wipe clears `sqlite_sequence`, so ids start from 1 as on a fresh install. A restore does not reset it. |
| 5 | The backup screen kept showing "Last backup <date>" for files the wipe had deleted. | Fixed: `AutoBackupRepository.forgetRuns()`. The toggle and the enqueued work are left alone (see `LocalDataRepositoryImpl`'s class doc). |

#### Evidence

The new tests, run against the code before the fix on 2026-11-12, all failed:

```text
LocalDataRepositoryImplTest
  deleteAll takes the rest's note and alarm down          expected:<[]> but was:<[Resting]>
  deleteAll takes a paused rest's note down               expected:<[]> but was:<[Rest paused]>
  deleteAll takes the idle nudge and its alarm down       expected:<[]> but was:<[Still training?]>
  an expiry that arrives after deleteAll posts nothing    expected:<[]> but was:<[Rest over]>
  deleteAll deletes every backup file, finished or not    expected:<[]> but was:<[titan-backup-2026-11-09-1433.json.tmp,
                                                            titan-backup-2026-11-09-1432.json, titan-auto-2026-11-08.json.tmp,
                                                            titan-auto-2026-11-01.json]>
  a new session can be started after deleting data that held an id near the limit
                                                          SQLiteFullException: database or disk is full (code 13 SQLITE_FULL)
RestoreLeftoversTest
  a restore takes the rest, the idle nudge and their alarms down
                                                          expected:<[]> but was:<[<2 scheduled alarms>]>
  an expiry for a session id the restore gave to another workout posts nothing
                                                          expected:<[]> but was:<[Rest over]>
```

With the fix they pass. With only the session check in `RestTimerBroadcasts` disabled, the two
"expiry" tests fail again with `Rest over`, so the check is tested on its own and not only
through the teardown.

### Needs the owner

- On a debug build with a dummy profile: start a rest, swipe the app away, relaunch, *Delete
  all data*, put the app in the background. No "Rest over" note should appear. Repeat with the
  rest paused, and once with a backup restore instead of the wipe.

---

## Backups in shared storage on Android 8 and 9 (#259)

Every manual export and every weekly automatic backup was written to
`getExternalFilesDir(null)/backups`, which is `/sdcard/Android/data/app.titan.gym/files/backups`,
on every Android version. Each file is the whole training history and the body weight, body fat
and measurement log in plain JSON, and a file the restore trusts. `minSdk` is 26. On Android
8.0 to 9 (API 26 to 28), Android documents that app-specific external storage can be read and
written by any app holding `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE`. Gallery,
file-manager and messaging apps commonly hold one. The privacy policy said these files were
protected by the app's sandbox, which on those versions was not true. The audit found this by
reading the source and the platform documentation. It had no emulator to observe it on.

### Decision

**Option 1 of the issue: below Android 10, backups live in app-private internal storage.**
`filesDir/backups` below API 29; `getExternalFilesDir(null)/backups` from API 29, with
`filesDir/backups` as the fallback when there is no external volume. The other two options were
encryption at rest, which needs key management the project has avoided so far, and accepting
and documenting the exposure. Option 1 was taken without waiting for the emulator check. The
exposure is the platform's documented behaviour on API 26 to 28. Moving the files to internal
storage there is safe whether or not the check confirms it, and it costs only reachability:
on those versions no file manager can browse to a backup any more.

| # | Finding | Resolution |
| --- | --- | --- |
| 1 | The weekly backup (`AutoBackupFiles.directory`), the manual export (`BackupViewModel`) and the share (`BackupSharing`) each resolved the directory themselves, with no SDK branch. | Fixed: one resolver, the domain port `BackupDirectory`, implemented once by `AppBackupDirectory` in `:core:data` and bound as a singleton. All three, and "Delete all data" through `AutoBackupFiles`, ask it. `BackupSharing` now takes the file, not its name. |
| 2 | On API 26 to 28 the directory is readable by other apps. | Fixed: internal storage below API 29 (`AppBackupDirectory.FIRST_SCOPED_SDK`). |
| 3 | Installs upgraded from a build that wrote to shared storage keep their old files there, where nothing would list them or delete them. | Fixed: the first resolution in a process moves every `titan-*.json` from the other root's `backups/` into the chosen one. It deletes the `titan-*.json.tmp` scratch files, which are truncated histories, and removes the old directory if it is left empty. The move is idempotent, and a file that cannot be moved is retried by the next process. It also covers a phone upgraded to Android 10 with its backups in `filesDir`. |
| 4 | The share must still work from internal storage. | Already declared: `file_paths.xml` has a `files-path` for `backups/` beside the `external-files-path`. `BackupSharingTest` now shares one file from each root and reads both back. |
| 5 | Backups in `filesDir` must stay out of Android's own backup (#180). | Already true: both rule files exclude `backups` in the `file` and `external` domains. Android 8 and 9 read `backup_rules.xml`, which has the `file` line. `BackupRulesTest` now reads the directory name from `BackupDirectory.NAME`. |
| 6 | The privacy policy described the folder as shared storage on every version. | Fixed in all four languages, effective 12 November 2026. |

#### Evidence

`AppBackupDirectoryTest` (Robolectric, `@Config(sdk = [28])` and the project's usual 34):

```text
below Android 10 backups are in internal storage
from Android 10 backups are in the external files directory
from Android 10 a device with no external volume keeps them in internal storage
below Android 10 an older install's backups are moved out of shared storage
the emptied shared-storage directory is removed
the move runs once per process and again harmlessly in the next
a backup already in place is kept over the copy in shared storage
from Android 10 backups left in internal storage follow the directory
nothing to move is not a failure, and creates nothing
```

All nine pass. Run on 2026-11-12 against the old resolution (`getExternalFilesDir(null) ?:
filesDir` on every version, nothing moved), six of the nine fail: all five at API 28, and the
API 34 test that follows backups out of internal storage.

### The emulator check: not run

The machine this was done on has no emulator and no Android system image, so the issue's
readability check was **not run**. Readable or not readable is still to be recorded. The
procedure:

1. Create and boot an API 28 AVD, for example
   `sdkmanager "system-images;android-28;google_apis;x86_64"`, then
   `avdmanager create avd -n api28 -k "system-images;android-28;google_apis;x86_64"` and
   `emulator -avd api28`.
2. Install a release-variant build of `main` *before* #259 (package `app.titan.gym`; the debug
   build's package is `app.titan.gym.debug`, and its path changes to match). Export a backup.
3. Install a dummy reader app that declares `READ_EXTERNAL_STORAGE` and targets API 28. Grant
   it the permission (`adb shell pm grant <reader> android.permission.READ_EXTERNAL_STORAGE`).
   Have it list and read `/sdcard/Android/data/app.titan.gym/files/backups`, for example by
   `File(...).listFiles()` and `readText()` in its `onCreate`, logging the result.
4. Record the result here: **readable** confirms the finding.
5. Install this change over it and open the backup screen. The reader should now find the
   directory gone, and the exported file should be in
   `/data/data/app.titan.gym/files/backups` (`adb shell run-as` on a debuggable build).
6. Repeat steps 2 to 4 on an API 29 AVD, with the reader still targeting API 28 (see the
   residual risk below).

### Residual risk

- **Android 10 with a reader in legacy storage mode.** The change keeps the external files
  directory from API 29, as the issue reads the platform. But Android's documentation lists
  "apps can no longer access files in any other app's app-specific directory within external
  storage" among the *Android 11* changes. That suggests an app in legacy storage mode on
  Android 10 (targeting API 28 or below, or `requestLegacyExternalStorage`) may still read
  the directory. This was not verified. If step 6 finds the file readable, change
  `FIRST_SCOPED_SDK` to `Build.VERSION_CODES.R`. That is one line plus the test's SDK levels,
  and the move then carries Android 10's files into internal storage on the next launch.
- **The files are still plain JSON.** Root access can still read them, and so can anyone who
  holds the unlocked phone and uses *Share*. Encryption at rest (option 2) is not done.
- **A file that cannot be moved stays in shared storage** until a later process manages it,
  for example when the disk is too full for the copy. Nothing reports it, and "Delete all
  data" does not reach it in that window.
- **Copies that already left.** Another app may already have read or copied a backup from
  shared storage before the upgrade. Nothing can recall that.
- **Reachability on Android 8 and 9.** A backup there can only be taken off the phone with
  *Share* or *Save a copy…* right after an export. The weekly automatic backups cannot be
  copied off at all, and a file manager no longer finds any backup. The backup screen's
  sentences do not say where backups live, so none needed changing.

### Needs the owner

- Run the emulator check above (steps 1 to 5 on API 28) and record **readable** or **not
  readable** here and on #259.
- Run step 6 on API 29. If the file is readable there, move `FIRST_SCOPED_SDK` to
  `Build.VERSION_CODES.R` and update the privacy policy's "Android 10 and later" in all four
  languages, here and on the site.
- Mirror the privacy policy change (new effective date 12 November 2026) into the site
  repository's four `privacy.mdx` pages, as `compliance-checklist.md` requires.
