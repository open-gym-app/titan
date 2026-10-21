# Releasing

How a commit on `main` becomes a build on Google Play, a GitHub Release and, through F-Droid's
own build of the same tag, a third channel. Written for someone doing it for the first time:
nothing below runs on a laptop except `git tag`, and the very first upload.

## The shape of it

`.github/workflows/release.yml` runs once `ci.yml` has passed on a push, and does one of two
things.

| Push | Version | Google Play | GitHub |
| --- | --- | --- | --- |
| a commit on `main` | the next candidate, `v1.2.4-rc.N` | **internal** track, published | pre-release |
| a tag `v1.2.4` | the release | **production** track, published | release, "latest" |

F-Droid (#210) builds release tags only, from source, and verifies its build against the APK
on the GitHub Release.

Nothing waits for a person in the Play Console. A candidate reaches the internal testers once
Play has processed it; a release reaches every user once Play's review passes. **Pushing a
release tag is the decision to ship.** Promotion to closed and open testing (#47) is still
done in the Console, from a candidate the workflow published.

## The version comes from the tag

A release is a tag named `v<MAJOR>.<MINOR>.<PATCH>`, semver by what changed since the last
one, pushed by a person. A release candidate is `v<MAJOR>.<MINOR>.<PATCH>-rc.<N>`, cut by the
workflow on every push to `main`: the next patch after the last release, with the next free
`N` (`v1.0.0-rc.1` before any release exists). Its tag is pushed before anything is built, so
two candidates can never share a number; a run that fails afterwards burns its number.

| | `versionName` | `versionCode` |
| --- | --- | --- |
| `v1.2.3-rc.1` | `1.2.3-rc.1` | `1020301` |
| `v1.2.3-rc.2` | `1.2.3-rc.2` | `1020302` |
| `v1.2.3` | `1.2.3` | `1020399` |
| no tag (every local build, CI's `assembleRelease`) | `0.0.0-dev` | `1` |

`versionCode` is `MAJOR × 1000000 + MINOR × 10000 + PATCH × 100 + N`, with `N` the candidate
number and 99 for the release. It is derived — by `ReleaseVersion` in `build-logic`, from the
`-PreleaseVersion=` property the workflow passes — and never edited: `app/build.gradle.kts`
has no number to bump. Play refuses a code it has seen and never lets one go backwards, so
every candidate needs its own code, and the scheme keeps them ordered:
`1.0.0-rc.1 < 1.0.0-rc.2 < 1.0.0 < 1.0.1-rc.1`. `MINOR` and `PATCH` stop at 99 and `N` at 98,
which the parser enforces.

Candidates always bump the patch. For a minor or major release, tag `v1.1.0` or `v2.0.0`
directly; its code is above every candidate before it all the same.

To see what a tag resolves to before pushing it:

```bash
./gradlew -q :app:printReleaseVersion -PreleaseVersion=1.2.3-rc.1
```

## Before the first release (#44)

Everything in this section is done once, by the person who owns the Play developer account.
None of it is in the repository; all of it is what the workflow's `release` job consumes.

### The upload keystore

Generated once, with a 25-year validity, and never committed (`*.jks` and `keystore.properties`
are gitignored):

```bash
keytool -genkeypair -v \
  -keystore upload.jks -alias upload \
  -keyalg RSA -keysize 4096 -validity 9125
```

This is the **upload** key. With Play App Signing enrolled, Google holds the app signing key
and re-signs what we upload; the upload key is what the workflow signs with and what Play
checks an upload against. Losing it is recoverable through Play support with the app signing
key, after days of forms, so a second copy lives outside GitHub:

- Off-site copy: _to be filled in by #44 (a password manager's attachment is fine; say which)._
- App signing certificate SHA-256 (from the Console, once enrolled): _to be filled in by #44._
  #210's F-Droid metadata and any future App Links need it.

To check a signed APK was made with it: `apksigner verify --print-certs titan-1.2.3.apk`.

### The `release` environment

Every secret is an **environment** secret on a GitHub Actions environment named `release`.
Never a repository secret: a fork's pull request cannot reach an environment. The environment
has **no required reviewers**: one would hold every push to `main` until someone approved it.
The names match `keystore.properties.example`, which is what a local signed build reads
instead.

| Secret | Contents |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 upload.jks` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `upload` |
| `KEY_PASSWORD` | the key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | the Play service account's JSON key, verbatim |

`GRADLE_ENCRYPTION_KEY` is a repository secret and only encrypts the Gradle cache; it is the
same one `ci.yml` uses.

### Play Console

1. The developer account, and an app entry for Titan in it.
2. **Play App Signing** enrolled for the app, with the upload certificate from the keystore
   above registered.
3. A service account with the rights to release to production and to testing tracks on this
   app only (Users and permissions → Invite new user → the service account's email), its JSON
   key in `PLAY_SERVICE_ACCOUNT_JSON`.
4. **The first bundle is published by hand.** Play binds the package name to the app on the
   first upload, and it only accepts that one through the Console — the API returns
   "package name not found" until then — and it accepts only drafts through the API until the
   app's first release is out. See [The first release](#the-first-release).

## Writing the changelog

Release notes live in `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`, one
file per release per locale, for the four locales the app ships in: `en-US`, `fr-FR`, `es-ES`,
`pt-BR`. The file is named after the **release's** code (`1000099.txt` for `1.0.0`). Play
shows it as "What's new", F-Droid shows it as the version's changelog, and the GitHub Release
body is the `en-US` file. One source, three consumers.

A release refuses to build without all four. A candidate goes up with the notes of the release
it precedes if they exist, and with none if they do not — most candidates are cut before
anyone has written them — and its GitHub pre-release then says which commit it was built from.

Plain text, under **500 characters** per locale (Play's limit; the build checks it), in the
words of the glossary in `docs/TRANSLATION.md`.

To check the four files exist and fit before tagging:

```bash
./gradlew :app:syncPlayReleaseNotes -PreleaseVersion=1.2.3
```

That task is also what puts the changelog where the Play upload reads it from
(`app/src/release/play/`, generated and gitignored — edit the changelog, never that).

The rest of `fastlane/metadata/android/` — descriptions, screenshots, the icon — is #46's.

## Cutting a release

1. **A candidate of that commit has been tested.** Every push to `main` published one to the
   internal track; install it from Play, or the APK on its GitHub pre-release.
2. **The changelog is merged**, in all four locales, under the release's code
   (`printReleaseVersion` above).
3. **Tag and push.** An annotated tag on the commit to ship, and nothing else in the push. The
   candidate's own tag points at it already:

   ```bash
   git fetch --tags
   git tag -a v1.2.3 -m "v1.2.3" v1.2.3-rc.2
   git push origin v1.2.3
   ```

4. **Watch** `CI` run on the tag, then the `Release` workflow under Actions. The
   `Reproducible build` job resolves the version, checks the changelog, builds the unsigned
   APK twice from a clean tree and fails if the two differ. The `Sign and publish` job is the
   only one that sees a secret.
5. **What comes out**, in order:
   - a GitHub Release `v1.2.3` with `titan-1.2.3.apk`, `titan-1.2.3.apk.sha256`,
     `changelog-1.2.3.txt`, `mapping-1.2.3.txt.gz` and `build-info.txt`, the body being the
     `en-US` changelog;
   - the bundle on Play's **production** track, published to everyone, with the four
     changelogs as "What's new";
   - workflow artifacts: the R8 mapping (90 days), the signed AAB and APK (14 days), the
     toolchain record, and the native debug symbols if the build ever has any.

A candidate's run is the same, minus the second build and the comparison: it goes to the
**internal** track, and its GitHub Release is a pre-release that is never "latest".

### Where the mapping file goes

Play deobfuscates crash reports with `mapping.txt`, and it needs the one from the exact build.
AGP packs it into the AAB, so the Console has it for every bundle the workflow uploaded; the
GitHub Release carries it for good beside the APK; the workflow artifact is the copy that
exists for a rehearsal, which has neither.

### The first release

Until the app has had a release, Play only takes uploads from the Console, so the first one
is by hand. Expect the first candidate's run to be red at *Publish the bundle to Play* and
everything before it to be green, the GitHub pre-release included. Then:

1. Download the `titan-<version>` artifact from that run and upload `titan-<version>.aab` to
   the internal track in the Console, by hand. This is the upload that binds `app.titan.gym`.
2. Roll that release out to the internal testers from the Console. From then on the app is no
   longer a draft, and the API may publish.

Every later candidate and release goes through the API.

## Rehearsing

The `Release` workflow can be dispatched by hand (Actions → Release → Run workflow) with a
`version` and `dry_run` left **on**. It builds from the branch you dispatch it on, signs and
bundles exactly as a release would, and stops at each upload step: no tag, no GitHub Release,
nothing on Play. The signed APK is in the `titan-<version>` artifact for fourteen days, to
install on a phone. A release `version` needs a changelog under its code like a real one does;
`1.0.0` has one.

A rehearsal is what to do after any change to `release.yml`, `app/build.gradle.kts`'s signing
or `play` blocks, or the secrets.

## When it fails

**A pushed tag is never deleted or moved.** Someone may have fetched it; F-Droid may have
built it; a `versionCode` may be spent on Play. Whatever happened, the fix is either to run
the same tag again or to cut the next one — never to make `v1.2.3` mean something else. A
failed candidate needs nothing at all: the next push to `main` takes the next number.

| Where it failed | What happened | What to do |
| --- | --- | --- |
| `Resolve the version` | the tag is not `vMAJOR.MINOR.PATCH` or `-rc.N` | Delete the tag: it never was a version, nothing consumed it. This is the one exception. |
| `Check the changelog` | a release's locale file is missing, or any is over 500 characters | Fix the changelog on `main`, cut the next patch. The tag stands, released nowhere. |
| `Assert the two builds are byte-identical` | the build is not reproducible | Find what varies (a timestamp, an absolute path, an unordered collection in a generated file — `unzip -l` both APKs and diff the entries). Fix on `main`, cut the next patch. |
| `Sign and publish`, before any upload | a secret, the runner, a Gradle failure | Fix the cause, then **Re-run failed jobs** on the same run. Nothing was uploaded, and the build job's result is reused. |
| `Create the GitHub Release` | GitHub API | **Re-run failed jobs.** The step updates a release that already exists rather than failing on it. |
| `Publish the bundle to Play` | the Play API, or the first release (above) | The GitHub Release is already out; the two channels are independent. For a transient error, **Re-run failed jobs**. For a bundle Play rejects outright, the AAB is in the `titan-<version>` artifact for fourteen days: upload it by hand, or fix on `main` and cut the next one. |
| `Publish the bundle to Play` says the `versionCode` already exists | the bundle went up on an earlier attempt, before the run failed | Nothing to retry: check the Console shows the build on its track, and that the GitHub Release exists. The run stays red. |

Re-running replays `release.yml` **as it was when the run started**. When the bug is in the
workflow file itself, fix it on `main` and dispatch the workflow with the tag's `version` and
`dry_run` **off**: it checks out the tag's sources and runs the current workflow over them,
uploads included. The GitHub Release is updated in place; if the bundle had already reached
Play, the last step is the expected red from the row above.

## Reproducibility, for F-Droid

F-Droid (#210) does not take our APK on trust. Its build server checks out the tag, builds the
unsigned APK from source and compares it to the one on the GitHub Release with the signature
block removed; if the bytes match, F-Droid ships our signed APK and a user can move between
the F-Droid and GitHub builds without an uninstall. That comparison is what the
`Reproducible build` job rehearses on every release tag: two builds from a clean tree, no build cache,
identical or the release stops there.

`build-info.txt` on every GitHub Release records what a verifier needs to line up: the commit,
the JDK, Gradle, AGP and Kotlin versions, and the unsigned APK's SHA-256. What keeps the build
deterministic today:

- the version is a function of the tag, not of a date or a build number;
- AGP's `version-control-info.textproto` in the APK records the commit and a `$PROJECT_DIR`
  placeholder, not the checkout path;
- the licences JSON (`aboutlibraries`) is generated offline from the dependency graph, with
  no timestamp;
- dex files are stored, not deflated (`packaging.dex.useLegacyPackaging = false`), so what a
  verifier compares is R8's output with nothing between.

F-Droid's own verification (`fdroid build --verify`, on a machine that is not ours) is #210's
step, and the place a difference that this job cannot see — a different JDK's javac, a
different `PATH` — would surface.
