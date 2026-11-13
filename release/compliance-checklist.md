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
| Effective date | 12 November 2026 |

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

Checked at commit `b4d8f19e67687cb8df8b9f08b132bf706ef65992` (`main`, "fix: let the rest sheet
stay down and step the total with ±15s (#245)") with:

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
  Compose, Dagger/Hilt, Guava (Hilt's dependency), Okio, `io.coil-kt.coil3` (core and compose
  only — **no** `coil-network-*` artifact, so images load from the bundled assets and nothing
  else), `com.google.accompanist:accompanist-drawablepainter` (pulled in by `coil-compose` to
  draw a `Drawable`) and `com.mikepenz:aboutlibraries` (reads a licence list generated at
  build time).
- **Android backup opted out** for the database, settings, drafts and backup files:
  `android:dataExtractionRules` and `android:fullBackupContent` (#180, asserted by
  `BackupRulesTest`).
- **No foreground service type** is declared. WorkManager's `SystemForegroundService` is merged
  in without a `foregroundServiceType`, and the app never starts it, so the Play foreground
  service declaration does not apply.

- **Heart rate is not collected.** `workout_set` has `heart_rate_avg` and `heart_rate_max`
  columns, but no screen in this version writes them: the active session only carries them
  through when a set is edited. A version that records heart rate (by hand, or from Health
  Connect in #54) must add it to the privacy policy's list of health data and to the Health
  apps declaration before it ships.

Re-run the two commands at the release commit and update the hash above. Any new permission or
library means revisiting every section below.

## Permissions

Every `uses-permission` in the merged release manifest. **#48's security audit
(`release/security-audit.md`) must carry this list byte for byte.**

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
| **Health apps** | Yes: *Fitness › activity and fitness tracking* and *body measurements*. Health data (body weight, body fat, body measurements) is stored on device only, never transmitted, never used for ads. Not connected to Health Connect in this version (#54 will need a new declaration). Privacy policy names health data explicitly (§ Health data). | ☐ |
| **Exact alarm permission** (`USE_EXACT_ALARM`) | Core functionality: *Timer* — the rest timer between sets must go off on the second, with the screen off. Also used for user-scheduled workout reminders. | ☐ |
| **Foreground service permissions** | Not applicable: no `foregroundServiceType` declared (see Evidence). | ☐ |
| **Data safety** | See below. | ☐ |

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
| Does your app collect or share any of the required user data types? | **No** | "Collect" means transmitted off the device. The app has no `INTERNET` permission and no SDK that could transmit. |
| Is all of the user data collected by your app encrypted in transit? | Not asked once "No" is answered. | |
| Do you provide a way for users to request that their data is deleted? | Not asked once "No" is answered. In-app anyway: Settings › Delete all data. | |

**Data processed only on the device.** Play's guidance excludes data that is processed only
on the device and never sent off it, which describes everything here. Health data (body
weight, body fat, measurements) and fitness data (workouts) are *processed on the device*
and stored there. They are not *collected*, so they are not declared, and the form does not
ask about them further.

**The export file.** Settings › Backup & restore › Export writes a JSON file of all data and
hands it to the Android share sheet or a folder the user picks. That transfer is initiated
by the user, to a destination the user chooses, and the app never sees where it goes. Under
Play's definitions it is neither collection nor sharing. It is described in the privacy
policy (§ When your data leaves the phone) so the answer above is not a surprise to anyone
who reads both.

**Security practices.** Nothing to declare: no data is transmitted. The app is independently
verifiable: the source is public under GPL-3.0.
