# Play Console compliance checklist

The answers Titan gives in Play Console › Policy › App content, the evidence behind each one,
and the privacy policy they rest on. The next release diffs against this file: if a dependency,
a permission or a stored field changes, the matching answer here changes in the same pull
request, or the release does not go out (#45).

## Privacy policy

| | |
| --- | --- |
| Source of truth | [`PRIVACY.md`](PRIVACY.md), with [`PRIVACY.fr.md`](PRIVACY.fr.md), [`PRIVACY.es.md`](PRIVACY.es.md), [`PRIVACY.pt-BR.md`](PRIVACY.pt-BR.md) |
| Public URL (Play listing) | <https://open-gym-app.github.io/privacy> |
| Translated URLs | <https://open-gym-app.github.io/fr/privacy>, <https://open-gym-app.github.io/es/privacy>, <https://open-gym-app.github.io/pt-BR/privacy> |
| Site source | `open-gym-app/open-gym-app.github.io`: `docs/src/pages/privacy.mdx` and `docs/i18n/<locale>/docusaurus-plugin-content-pages/privacy.mdx` |
| Effective date | 25 November 2026 |

The site pages repeat this directory's text word for word (the app name is a variable there,
so a rename touches one line). **A change to the policy is a change to both repositories**,
made in the same week, with the effective date bumped in all eight files.

Check before every release:

```bash
for p in privacy fr/privacy es/privacy pt-BR/privacy; do
  curl -s -o /dev/null -w "%{http_code}  $p\n" "https://open-gym-app.github.io/$p"
done   # four lines of 200
```

## Evidence: what the release build contains

Checked at commit `85f476815974090e24fe6c8ee4713a36750d0e8f` (`main`, "fix(deps): update
dependency org.robolectric:robolectric to v4.18.1 (#359)") with:

```bash
./gradlew :app:processReleaseMainManifest \
          :app:dependencies --configuration releaseRuntimeClasspath
# merged manifest: app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
# provenance:      app/build/outputs/logs/manifest-merger-release-report.txt
```

- **No `INTERNET` permission** in the merged release manifest. `usesCleartextTraffic` is absent.
- **No analytics, crash-reporting, advertising or networking library** on the release runtime
  classpath. `grep -iE 'firebase|crashlytics|analytics|sentry|bugsnag|okhttp|retrofit|ktor|play-services|gms|admob|datatransport'`
  over the dependency report returns nothing. The groups present are AndroidX, Jetpack
  Compose (with JetBrains' multiplatform coordinates for it), Kotlin and kotlinx, Dagger/Hilt,
  `com.google.guava:listenablefuture` (one interface, not Guava itself), Okio, annotation jars
  (`javax.inject`, `jakarta.inject`, `jspecify`, `findbugs`, `org.jetbrains:annotations`),
  `io.coil-kt.coil3` (core and compose only — **no** `coil-network-*` artifact, so images load
  from the bundled assets and the app's own photo folder and nowhere else),
  `com.google.accompanist:accompanist-drawablepainter` (pulled in by `coil-compose` to draw a
  `Drawable`) and `com.mikepenz:aboutlibraries` (reads a licence list generated at build time).
- **Android backup opted out** for the database, settings, drafts, backup files and progress
  photos (`files/photos/`): `android:dataExtractionRules` and `android:fullBackupContent` (#180,
  asserted by `BackupRulesTest`).
- **One foreground service, typed `dataSync`.** `:core:data` sets `foregroundServiceType` on
  WorkManager's `SystemForegroundService` (`exported="false"`), and `HistoryImportWorker`, the
  import from another app, is the only work that runs in it. The import starts from the import
  screen, with a file the user picked there, and the service ends with it.
- **Heart rate is stored if an imported file holds it, and never shown.** `workout_set` has
  `heart_rate_avg` and `heart_rate_max` columns. The plain CSV import fills them from
  `avg_heart_rate` and `max_heart_rate`; no other parser does, and the app measures nothing. No
  screen draws or edits them and no calculation reads them, the calorie estimate included: the
  active session carries them through when a set is edited, and a backup and a restore carry
  them too. Heart rate is in the privacy policy's list of health data and in the Health apps
  declaration below. Reading it from Health Connect (#54) changes both answers again.
- **Progress photos are never in an export.** The image files are in `files/photos/`, and no
  backup carries them or the rows that name them. `PhotoCaptureProvider` exposes only the
  camera's scratch directory, not that folder. No camera or storage permission is declared: the
  camera app takes the picture and Android's photo picker returns the one the user chooses.

Re-run the two commands at the release commit and update the hash above. Any new permission or
library means revisiting every section below.

## Permissions

Every `uses-permission` in the merged release manifest. **#48's security audit
(`release/security-audit.md`) must carry this list byte for byte.**

| Permission | Declared by | Justification |
| --- | --- | --- |
| `android.permission.POST_NOTIFICATIONS` | `:core:data` | Posts the workout reminder, rest-timer alert, unfinished-session note and progression notifications, and the progress and the end of an import from another app. Requested at runtime, the first time the user turns a notification on. |
| `android.permission.SCHEDULE_EXACT_ALARM` (`maxSdkVersion="32"`) | `:core:data` | Exact alarm for the background rest timer, workout reminders and the unfinished-session note on Android 12 and below, where it is granted at install. |
| `android.permission.USE_EXACT_ALARM` | `:core:data` | Exact alarm for the background rest timer, workout reminders and the unfinished-session note on Android 13+. Restricted by Play to alarm, calendar and **timer** apps; a user-started rest timer is a timer. Falls back to an inexact alarm if refused. |
| `android.permission.RECEIVE_BOOT_COMPLETED` | `:core:data`, `androidx.work:work-runtime` | Reschedules reminder alarms after a reboot (`AlarmManager` keeps none) and lets WorkManager reschedule the weekly auto-backup. |
| `android.permission.VIBRATE` | `:core:ui` | The buzz at the end of a rest (`RestExpiryAlert`). |
| `android.permission.WAKE_LOCK` | `androidx.work:work-runtime` | WorkManager holds a wake lock while the weekly auto-backup or an import from another app runs. |
| `android.permission.ACCESS_NETWORK_STATE` | `androidx.work:work-runtime` | WorkManager's network constraints. The app sets none, on the weekly auto-backup or on an import, and the permission reads connectivity state only; it cannot open a socket. |
| `android.permission.FOREGROUND_SERVICE` | `:core:data`, `androidx.work:work-runtime` | Runs an import from another app as WorkManager's foreground service (`HistoryImportWorker`), so it is not stopped ten minutes in or cancelled when the user leaves the screen. No other work is promoted to the foreground. |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | `:core:data` | The permission Android 14+ requires for that service's type, `dataSync`: the import reads a file the user picked into the app's database. Grants no network access. |
| `app.titan.gym.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core:core` | Signature-level permission AndroidX uses to keep runtime-registered receivers private to the app. |

## Console declarations

Record the date each is submitted. An answer changes only with this file.

| Declaration | Answer | Submitted |
| --- | --- | --- |
| **Privacy policy** | <https://open-gym-app.github.io/privacy> | ☐ |
| **Ads** | No, the app does not contain ads. | ☐ |
| **App access** | All functionality is available without special access: no login, no account, no gated content. | ☐ |
| **Content rating** | See Content rating (IARC) below. | ☐ |
| **Target audience and content** | Ages **13–15, 16–17 and 18+**. Not designed for children; no features appeal to children under 13. No social features, no user-to-user communication, no ads. | ☐ |
| **News app** | No. | ☐ |
| **Government app** | No. | ☐ |
| **Financial features** | None. | ☐ |
| **Health apps** | Yes: *Fitness › activity and fitness tracking* and *body measurements*. Health data (body weight, body fat, body measurements, progress photos, calories, and heart rate if an imported file holds it) is stored on device only, never transmitted, never used for ads. Calories are estimated on the device from the workout and the user's body weight, typed in by the user, or read from an imported file. Heart rate is neither measured nor shown (see Evidence). Not connected to Health Connect in this version (#54 will need a new declaration). Privacy policy names health data explicitly (§ Health data). | ☐ |
| **Exact alarm permission** (`USE_EXACT_ALARM`) | Core functionality: *Timer* — the rest timer between sets must go off on the second, with the screen off. Also used for user-scheduled workout reminders. | ☐ |
| **Foreground service permissions** | `FOREGROUND_SERVICE_DATA_SYNC`. See Foreground service below. | ☐ |
| **Data safety** | See below. | ☐ |

## Foreground service

Play Console › Policy › App content › Foreground service permissions. The form asks, for each
type, what it is used for, what the user loses if the work is deferred or interrupted, and for a
link to a video of the feature.

| Field | Answer |
| --- | --- |
| Type | `dataSync`, for an import into the app: local processing of a file, with no network transfer |
| What it does | Settings › Import from another app reads a training history the user exported from another app, or wrote as a CSV, and adds it to the app's database |
| Started by | The user, from that screen, by picking the file. It is never scheduled, and a run that was interrupted is not run again unless the user asks |
| Why a foreground service | A large history takes minutes, and it has to outlive the screen. Background work can be stopped after ten minutes, and the history is written in one transaction, so a stopped import has written nothing |
| If deferred | The user has just picked a file and is waiting for it. An import that starts later adds their history at a moment they did not choose, and makes a set they are logging wait |
| If interrupted | Nothing is imported, and the user has to run it again from the start |
| What the user sees | The phase and count on the import screen, and an ongoing, silent notification with the same progress (where notifications are allowed). A tap opens the import screen |
| How the user stops it | *Cancel* on the notification, or on the import screen. Nothing is kept of a cancelled import |
| Ends | With the import. The service is not kept for anything else |
| Video | — (pick a file, leave the screen, the notification's progress, Cancel) |

## Content rating (IARC)

Play Console › Policy › App content › Content rating. The questionnaire is IARC's, and the
certificate it issues covers every storefront that uses IARC ratings, Play included.

**Status: not submitted.** The answers below are prepared from the app as built; submitting
them, and recording the certificate, is the remaining step.

### Category

**All other app types.** Titan has levels, quests and loot, but it is a training log those sit
on top of rather than a game: there is no play outside logging a real workout. Choosing *Game*
would ask about game content that does not exist.

### Answers

| Question | Answer | Why |
| --- | --- | --- |
| Violence, blood or gore | No | |
| Sexual content or nudity | No | |
| Profanity or crude humour | No | |
| Drugs, alcohol or tobacco | No | |
| Gambling, simulated or real | No | Loot drops are rolled from finishing a workout, cost nothing, cannot be bought and have no value outside the app |
| Fear or horror | No | |
| Users can interact or exchange content | No | No accounts, no sharing inside the app, no network access |
| Shares the user's location | No | No location permission |
| Digital purchases | No | No billing library, no paid content |
| Unrestricted internet access | No | The release manifest strips `INTERNET` (`app/src/main/AndroidManifest.xml`), guarded by `NetworkPolicyTest` |

Expected result: the lowest rating in every territory (ESRB Everyone, PEGI 3, USK 0).

### Certificate

| Field | Value |
| --- | --- |
| Submitted on | — |
| IARC certificate ID | — |
| Ratings issued | — |
| Commit the answers were checked against | — |

## Data safety

| Question | Answer | Why |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **No** | "Collect" means transmitted off the device. The app has no `INTERNET` permission and no SDK that could transmit, and its foreground service opens no connection. |
| Is all of the user data collected by your app encrypted in transit? | Not asked once "No" is answered. | |
| Do you provide a way for users to request that their data is deleted? | Not asked once "No" is answered. In-app anyway: Settings › Delete all data. | |

**Data processed only on the device.** Play's guidance excludes data that is processed only
on the device and never sent off it, which describes everything here. All of it is *processed
on the device* and stored there. None of it is *collected*, so it is not declared, and the form
does not ask about it further:

- Health data: body weight, body fat, measurements, calories, and heart rate if an imported file
  holds it.
- Fitness data: workouts with their sets, distances, times, elevation and steps, the user's
  rating of a workout, and the weekly goal.
- Photos: progress photos, re-encoded without the camera's metadata.

**The export file.** Settings › Backup & restore › Export writes a JSON file of all data except
progress photos and hands it to the Android share sheet or a folder the user picks. That
transfer is initiated by the user, to a destination the user chooses, and the app never sees
where it goes. Under Play's definitions it is neither collection nor sharing. It is described in
the privacy policy (§ When your data leaves the phone) so the answer above is not a surprise to
anyone who reads both. Progress photos have no way out of the app at all.

**The file an import reads.** Settings › Import from another app reads one file the user picks
in Android's document picker, and adds what it holds to the database. Titan signs in to no other
app, writes nothing back and transmits nothing. It keeps a read grant on that one document until
the user closes the result or starts another import, so an interrupted import can be run again.
Reading a file into the app's own storage is not collection.

**Security practices.** Nothing to declare: no data is transmitted. The app is independently
verifiable: the source is public under GPL-3.0.
