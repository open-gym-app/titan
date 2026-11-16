# Security audit

What the release build exposes, what controls each exposure, and the evidence that the control
holds. This file describes the app as it is; when a change moves a boundary, the section for it
changes in the same pull request. Items still open are listed at the end, each with an issue.

Checked at commit `b9842deda93ae51ef0df31bf624c02f5bcc923fb` (`main`) on 2026-11-14, on the
output of `./gradlew :app:assembleRelease :app:bundleRelease`, the same tasks `release.yml` runs.
The signing certificate was since read from the first pipeline artifacts (`v1.0.0-rc.3`, see
*Release pipeline*). Re-run the rest of *The release artifact* on the first `v1.0.0` APK and
update the commit above.

## Threat model

Titan is single-user and offline. It has no account, no server and no network permission. The
data it holds (training history, body weight, body fat and measurements) never leaves the phone
unless the user exports it. The parties that can act against it are:

| Who | What they can send | Boundary |
| --- | --- | --- |
| Another app on the phone | Intents to exported components, `titan://` links | Exported components, deep links |
| A web page the user taps | `titan://` links (the filter is `BROWSABLE`) | Deep links |
| Whoever provides a backup file | A JSON document the restore reads | Restore |
| Another app with storage access | Reads of shared storage | On-device storage |
| A library upgrade | A permission, a socket | Manifest merge, network config |
| Anyone with write access to the repository | Workflows, tags | Release pipeline |

Root access, a compromised OS and someone holding the unlocked phone are out of scope: the
platform's per-app sandbox and the lock screen are the controls the design rests on.

## The release artifact

Decoded with apktool 3.0.3 and checked with build-tools 36.0.0.

### Secrets

```sh
apktool d -f app-release.apk -o decoded
grep -riEl "api[_-]?key|password|secret|token|BEGIN (RSA|PRIVATE)" decoded
```

Twenty files matched, none in `res/` or `assets/`. Every match is library code, identified by
looking each obfuscated class up in `mapping.txt`:

| Match | Where |
| --- | --- |
| `Password`, `password`, `NumberPassword` | Compose text input, semantics and autofill (`KeyboardType`, `SemanticsProperties`, `ContentType`, `CoreTextFieldSemanticsModifier`, `InputMethodManagerImpl`, `AndroidComposeViewAccessibilityDelegateCompat`), `AccessibilityNodeInfoCompat` |
| `Token`, `token` | `View.getWindowToken` and `Parcel.writeInterfaceToken` call sites in Compose, AndroidX Core and Room; kotlinx.serialization's JSON reader messages ("Unexpected JSON token"); `Intrinsics` ("valid token"); `DebugStringsKt` (`RESUME_TOKEN`) |
| `token=TRBZRIRI6RCVZNRZP4PCYXJ3RM` | `META-INF/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/verification.properties`, JetBrains' published SDK verification token, shipped in the library's jar |

### Files that must not ship

```sh
unzip -Z1 app-release.apk | grep -iE 'keystore|local\.properties|\.git|fixture|\.jks|\.p12|\.pem'
```

Nothing. The only non-library, non-resource entries are `assets/seed/` (the exercise catalogue
SQL and its images), `assets/dexopt/` (the baseline profile) and
`META-INF/version-control-info.textproto`, which records the commit the APK was built from.
That commit is public once the repository is, and it is what a reproducible build is checked
against.

### Native code and the 16 KB page size

The app has no native code of its own, but two AndroidX libraries bring some:
`libdatastore_shared_counter.so` (DataStore) and `libandroidx.graphics.path.so` (Compose
graphics), for all four ABIs.

```sh
zipalign -c -P 16 -v 4 app-release.apk     # every lib/*.so "(OK)", "Verification successful"
readelf -lW lib/arm64-v8a/*.so | grep LOAD # alignment 0x4000 on every segment; same for x86_64
```

The libraries are stored uncompressed (`extractNativeLibs="false"` in the merged manifest) at
16 KB-aligned offsets, and every `LOAD` segment is 16 KB-aligned, which is what Play requires
for targetSdk 35 and above.

### Deobfuscation

R8 runs on the release build (`isMinifyEnabled`, `isShrinkResources`). Every class in the APK
names its mapping: the `SourceFile` attribute is `r8-map-id-<pg_map_id>`, and the same id heads
`mapping.txt`. The AAB carries that file as
`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`, byte for byte (same SHA-256),
so Play has the mapping for every bundle it is given, including the first one, which is uploaded
by hand. `release.yml` also passes `mappingFile` to the upload. The GitHub APK is built by the
same R8 run as the bundle, so the mapping Play holds is the one for the APK too.

```text
$ retrace mapping.txt trace.txt
java.lang.IllegalStateException: example
 at com.titan.core.data.notification.BootReceiver.handle(BootReceiver.kt:59)
 at kotlin.collections.SetsKt__SetsKt.setOf(SetsKt__Sets.kt:44)
 at com.titan.core.data.notification.BootReceiver.<clinit>(BootReceiver.kt:89)
```

from frames written as the release build reports them (`BootReceiver.a(r8-map-id-b2c6…:52)`).
A crash from the pre-launch report is checked the same way during the closed track (#47).

## Manifest

Read from the decoded release manifest.

### Permissions

The list below is `release/compliance-checklist.md`'s, byte for byte, and matches the merged
release manifest: eight `uses-permission` entries and AndroidX's signature permission.

| Permission | Declared by | Justification |
| --- | --- | --- |
| `android.permission.POST_NOTIFICATIONS` | `:core:data` | Posts the workout reminder, rest-timer alert, unfinished-session note and progression notifications. Requested at runtime, the first time the user turns a notification on. |
| `android.permission.SCHEDULE_EXACT_ALARM` (`maxSdkVersion="32"`) | `:core:data` | Exact alarm for the background rest timer, workout reminders and the unfinished-session note on Android 12 and below, where it is granted at install. |
| `android.permission.USE_EXACT_ALARM` | `:core:data` | Exact alarm for the background rest timer, workout reminders and the unfinished-session note on Android 13+. Restricted by Play to alarm, calendar and **timer** apps; a user-started rest timer is a timer. Falls back to an inexact alarm if refused. |
| `android.permission.RECEIVE_BOOT_COMPLETED` | `:core:data`, `androidx.work:work-runtime` | Reschedules reminder alarms after a reboot (`AlarmManager` keeps none) and lets WorkManager reschedule the weekly auto-backup. |
| `android.permission.VIBRATE` | `:core:ui` | The buzz at the end of a rest (`RestExpiryAlert`). |
| `android.permission.WAKE_LOCK` | `androidx.work:work-runtime` | WorkManager holds a wake lock while the weekly auto-backup runs. |
| `android.permission.ACCESS_NETWORK_STATE` | `androidx.work:work-runtime` | WorkManager's network constraints. The app sets none, and the permission reads connectivity state only; it cannot open a socket. |
| `android.permission.FOREGROUND_SERVICE` | `androidx.work:work-runtime` | Declared by WorkManager for long-running work. Unused: no work is promoted to the foreground. |
| `app.titan.gym.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core:core` | Signature-level permission AndroidX uses to keep runtime-registered receivers private to the app. |

### No network

- `INTERNET` is removed at merge time (`tools:node="remove"` in `app/src/main/AndroidManifest.xml`),
  so a library that declares it does not get it. `NetworkPolicyTest` guards the line.
- `android:networkSecurityConfig="@xml/network_security_config"`: cleartext refused and an empty
  `<trust-anchors />`, so a TLS connection opened anyway fails on the first handshake.
- `usesCleartextTraffic` is absent. No networking library is on the release classpath
  (`compliance-checklist.md` → *Evidence*).

### Android backup

`allowBackup="true"` with `dataExtractionRules` (Android 12+) and `fullBackupContent` (11 and
below), both excluding every file in *On-device storage* that holds user data. The two files
carry the same list; `BackupRulesTest` asserts it. The reasoning is in
`res/xml/data_extraction_rules.xml`.

### Not set

`debuggable`, `testOnly` and `usesCleartextTraffic` are absent from the release manifest.

## Exported components

Every component of the merged release manifest with `exported="true"`:

| Component | Reached by | Why it is exported, and what checks the input |
| --- | --- | --- |
| `com.titan.app.MainActivity` | Launcher; `VIEW` + `titan://session/…`, `titan://progression/…` | The launcher entry and the target of the app's own notification links. Every incoming intent is replaced before Navigation reads it (`forNavigation` in `MainActivity.kt`): only the action survives, plus the `titan://` link when the action is `VIEW`, so Navigation's `deepLinkIds`/`deepLinkExtras` and any other extras are dropped. See *Deep links*. |
| `com.titan.core.data.notification.BootReceiver` | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIMEZONE_CHANGED`, `TIME_SET`, `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` | System broadcasts only reach an exported receiver. All five are protected broadcasts that only the system can send. The receiver checks the action against the same list and ignores anything else; its only effect is `ReminderScheduler.sync()`, which is idempotent. |
| `androidx.work.impl.background.systemjob.SystemJobService` | `JobScheduler` | `permission="android.permission.BIND_JOB_SERVICE"`: only the system can bind. |
| `androidx.work.impl.diagnostics.DiagnosticsReceiver` | `adb` | `permission="android.permission.DUMP"`: shell and system only. |
| `androidx.profileinstaller.ProfileInstallReceiver` | `adb`, Play | `permission="android.permission.DUMP"`: shell and system only. |

Every other component is `exported="false"`: the three notification receivers
(`RestTimerReceiver`, `IdleSessionReceiver`, `ReminderReceiver`), the `FileProvider`, the
`androidx.startup` provider and WorkManager's and Room's internal components. The notification
receivers are reached only through this app's own `PendingIntent`s, and the rest-timer and idle
receivers check that the session they name is the one still open (`SessionRef`: id and
`created_at`) before acting.

### Deep links

The filter admits two hosts, `session` and `progression`, and `DeepLinks` in `:core:domain`
builds every link the app posts: `titan://session/new`, `titan://session/active/{id}` and
`titan://progression/quests`.

- A link with another host does not resolve to the app. Delivered explicitly anyway, it opens
  nothing and the app stays where it was.
- A link under a known host that names no screen (`titan://session/nonsense/42`) opens nothing
  either: `openDeepLink` (`TitanNavHost.kt`) asks `graph.hasDeepLink` first and ignores a link
  no destination matches, where `navigate` would throw.
- `titan://session/active/{id}` acts only on the session it names. The workout screen's
  controls (resume, pause, finish, discard) refuse unless the open session is that id, and a page
  for a missing or finished session shows no END or DISCARD.

| Test | Pins |
| --- | --- |
| `DeepLinkNavigationTest` | every posted link opens its screen over Home; an unknown host and an unknown path under a known host change nothing |
| `IncomingIntentTest` | Navigation's extras never reach the graph; a `titan://` link under any action but `VIEW` is dropped; a recreated activity does not reopen its launch link |
| `ActiveSessionViewModelTest` | a link to a missing or finished session leaves the running workout as it was, and discard and finish on that page do nothing to it |

## On-device storage

Paths for the release package `app.titan.gym`:

| File | Location | Holds | Android backup | Delete all data |
| --- | --- | --- | --- | --- |
| `titan.db`, `-wal`, `-shm`, `-journal` | `databases/` | Everything the user logs | Excluded | Tables cleared and the seed replayed; id counters reset |
| `datastore/user-preferences.preferences_pb` | `files/` | Settings, units, reminders | Excluded | Cleared |
| `in-progress-session.json` (and `.tmp`) | `files/` | The running workout's draft | Excluded | Cleared |
| `template-draft.json` (and `.tmp`) | `files/` | A template being edited | Excluded | Cleared |
| `backups/titan-*.json` | `files/` below Android 11; external files directory (`Android/data/app.titan.gym/files/`) from 11, or `files/` when there is no external volume | Manual exports and weekly automatic backups: the whole history in plain JSON | Excluded (`file` and `external` domains) | Deleted, in both roots |
| `datastore/auto-backup.preferences_pb` | `no_backup/` | When the weekly backup last ran | Never backed up (platform rule) | Run dates forgotten; the toggle is kept |
| WorkManager's database | `no_backup/` | The weekly job | Never backed up | Left; the job stays scheduled if the toggle is on |

Every location is app-private. Below Android 11 the external files directory can be read by apps
holding storage permissions, which is why backups stay in `files/` there
(`AppBackupDirectory.FIRST_SCOPED_SDK`; `AppBackupDirectoryTest` runs at API 28, 29 and 34).
"Delete all data" also takes down the rest timer's and idle nudge's notifications and alarms
(`LocalDataRepositoryImplTest`). A restore does the same (`RestoreLeftoversTest`).

**Not encrypted at rest, deliberately.** The data is one user's, on one device, behind the
per-app sandbox and the lock screen. SQLCipher or an encrypted backup format would add a native
library and a key the app would have to keep somewhere, and on this threat model the key would
sit behind the same sandbox as the data. What remains exposed: root access, and anyone using the
unlocked phone, who can open the app or *Share* a backup.

### Restore

A backup file is untrusted input: anyone can hand the user one. Before anything is replaced, the
restore:

- reads at most `ExportFormat.MAX_DOCUMENT_BYTES` (64 MiB);
- refuses a `file://` URI, or one under this app's own `FileProvider` authority;
- validates the document's shape, every range a screen would crash on, and every reference
  between rows (`BackupValidator`; `BackupValidatorShapeTest`, `BackupValidatorRangeTest`,
  `HostileBackupTest`);
- runs under `BackupLock`, so it cannot interleave with an export, the weekly backup or "Delete
  all data".

## File sharing

- Every share goes through `androidx.core.content.FileProvider` (`exported="false"`,
  `grantUriPermissions="true"`), authority `${applicationId}.fileprovider`. `file_paths.xml`
  scopes it to `backups/` under `files/` and under the external files directory, and to nothing
  else: not the drafts in `files/`, not the database. `BackupSharingTest` shares from both roots.
- No `Uri.fromFile(` and no `file://` URI is handed to another app anywhere in `src/main`. The
  one `file://` string in production code is `file:///android_asset/`, the scheme Coil reads the
  bundled exercise images with (`ExerciseImage.kt`). `Uri.fromFile` appears only in
  `BackupViewModelTest`, as the input the restore must refuse.

## Platform requirements (targetSdk 36)

| Requirement | Status |
| --- | --- |
| 16 KB page size | Met; see *Native code and the 16 KB page size* above. |
| Predictive back | On, by the platform default for target 36. Nothing in the tree overrides `onBackPressed` or handles `KEYCODE_BACK`; back goes through `OnBackPressedDispatcher`, Navigation and Compose's `BackHandler`. |
| Exact alarms | `USE_EXACT_ALARM` from Android 13, which the user cannot revoke. On Android 12 and 12L `SCHEDULE_EXACT_ALARM` can be revoked in *Alarms & reminders*. Every scheduler checks `canScheduleExactAlarms()` and falls back to `setAndAllowWhileIdle`, and the reminder settings say delivery may be late. Revoking kills the app and cancels its exact alarms with no broadcast, so `MainViewModel` re-arms the reminders on every launch. Re-granting sends `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, which `BootReceiver` answers by re-arming them as exact. The rest timer and idle nudge are not re-armed, for the reason `BootReceiver` gives: they belonged to a process that ended. |
| Orientation | Portrait on phones; ignored on displays 600dp and wider at target 36, accepted for v1 (manifest comment). |

## Release pipeline

The release job holds the upload keystore, its passwords, the Play service account and a
`contents: write` token. These controls stand between a pushed commit and that job:

- **The gate.** `release.yml` runs on `workflow_run` after `CI`, so the workflow file that runs is
  always the reviewed one on `main`, never one carried by a tag. Before the `release`
  environment is touched, `.github/scripts/release-gate.sh` (checked out from `main`, with every
  tag and branch) requires:
  1. `workflow_run.path` is `.github/workflows/ci.yml` and `head_repository` is this repository;
  2. `head_branch` is `vX.Y.Z` or `vX.Y.Z-rc.N`, and no branch of that name exists;
  3. `refs/tags/<name>^{commit}` equals `workflow_run.head_sha`;
  4. `head_sha` is an ancestor of `origin/main`.

  A version-named run that fails any check fails the gate, with the reason as an annotation. A
  manual run must give a version matching `^[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?$` and never
  publishes: both publish steps also require `github.event_name == 'workflow_run'`. The job runs
  only for the three kinds the gate writes (`candidate`, `release`, `dry-run`).
- **Secrets.** The keystore is decoded under `umask 077` and removed by an `if: always()` step.
  `KEYSTORE_PATH` is set on the build step only. Every checkout uses
  `persist-credentials: false`. `setup-gradle` is `cache-read-only: true`, so the release
  commit cannot seed `main`'s cache.
- **Runners.** The gate and release jobs run on `ubuntu-latest` unconditionally, never on the
  `CI_RUNNER` pool that pull requests use.
- **Pull requests.** `ci.yml` sets `permissions: contents: read`.
- **`GRADLE_ENCRYPTION_KEY`** is shared by pull-request runs and the release job. It encrypts
  configuration-cache entries only, and the configuration cache is off. Revisit if it is turned
  on.
- **Origin of the APK.** `titan-X.Y.Z.apk.sha256` proves integrity only. Origin is the signing
  certificate, whose two fingerprints are published in `RELEASE.md` → *Signing certificate*.
  `apksigner verify --print-certs` on `titan-1.0.0-rc.3.apk` (v2 scheme, the checksum file
  matching) gives the upload key's, so the `KEYSTORE_BASE64` secret holds the keystore the
  fingerprint was published from.

`make release-gate-check` (`.github/scripts/release-gate-test.sh`) runs the gate against a
throwaway repository on twenty cases (good tags, an unmerged commit, a `v*` branch carrying a
workflow named `CI`, a moved tag, a name that is both a branch and a tag, a foreign repository,
newline injection in the path and the dispatched version, and others). CI runs it in the *Seed,
translations and release gate* job; all twenty pass.

### Repository settings, read on 2026-11-16

Read with `gh api` GET requests. The organisation variable needs an organisation admin token,
hence the 403.

| Setting | Value |
| --- | --- |
| Visibility | `public`, organisation-owned |
| Collaborators | three, all `admin` |
| Default workflow token permissions | `default_workflow_permissions: read`, `can_approve_pull_request_reviews: false` |
| Rulesets | `main`: pull request required, no force push, no deletion. `v*` tags: only admins may create, update or delete |
| `main` branch protection | `protected: true` |
| Secret scanning, push protection | Enabled |
| `release` environment | `branch_policy` only, with custom deployment policies `main` (branch) and `v*.*.*` (tag); no required reviewer; `can_admins_bypass: true` |
| `release` environment secret names | `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`, the five `release.yml` reads |
| Org variable `CI_RUNNER` | **Not read** (403). CI run 43027325904 (2026-11-11) ran on `ubuntu-latest`. No longer used by the release jobs. |
| Release runs so far | 83: 77 skipped, 3 succeeded, 2 failed, 1 cancelled. Tags `v1.0.0-rc.2` and `v1.0.0-rc.3` are published as pre-releases |

The `release` environment admits only `main` and `v*.*.*` tags, `main` takes changes through a
pull request only, and only admins can create or move a `v*` tag, so a workflow on another branch
no longer reaches the release secrets. What remains: no reviewer approves a release run, so an
admin who pushes a tag on a merged commit releases it alone.

## Repository history

Scanned on 2026-11-14, before the repository went public, over every ref (130 commits):

```text
$ gitleaks git --log-opts="--all" --redact .
INF 130 commits scanned.
INF no leaks found
```

A manual pass found no `keystore.properties`, `local.properties`, keystore, key, certificate,
`google-services.json` or service-account file ever committed (`keystore.properties.example`
holds placeholders only), and no hard-coded credential matching the APK patterns above in any
commit. The only email address in the tree is `fixture@example.invalid`.

Commit metadata carries contributors' author emails, one of them a personal address on two
commits. They were kept as they are (decided in #300) and are public with the repository.

## Open items

| Item | Who | Issue |
| --- | --- | --- |
| Add a required reviewer to the `release` environment. | Owner | #300 |
| Run the device checks below on a phone and record the result here. | Owner | #48 |
| Deobfuscate a crash from the pre-launch report with the bundle's mapping. | Owner | #47 |

### Device checks

On a phone with the release build installed (`app.titan.gym`):

```sh
adb shell dumpsys package app.titan.gym | grep -A20 "requested permissions"
#   the eight permissions above and the signature permission, nothing else
adb shell am start -W -a android.intent.action.VIEW -d "titan://nonsense/42" app.titan.gym
#   does not resolve: the host is not in the filter
adb shell am start -W -a android.intent.action.VIEW -d "titan://session/nonsense/42" app.titan.gym
adb shell am start -W -a android.intent.action.VIEW -d "titan://session/active/999999" app.titan.gym
#   both: the app opens where it was, no crash (`adb logcat -b crash -d` empty)
adb shell am broadcast -n app.titan.gym/com.titan.core.data.notification.RestTimerReceiver
adb shell content query --uri content://app.titan.gym.fileprovider/backups/x
#   both refused: not exported
```

Then, with a rest running: swipe the app away, relaunch, *Delete all data*, background the app.
No "Rest over" notification may appear. Repeat with the rest paused, and once with a restore
instead of the wipe.
