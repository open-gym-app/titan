# Releasing

`.github/workflows/release.yml` does every upload after the first one.

## The flow

Every release, candidate or not, is a tag you push on a commit that is already on `main`.
Merging to `main` publishes nothing.

| Trigger | Version | Google Play | GitHub |
| --- | --- | --- | --- |
| CI passes on a pushed `vX.Y.Z-rc.N` tag | `X.Y.Z-rc.N` | published on **internal** | pre-release |
| CI passes on a pushed `vX.Y.Z` tag | `X.Y.Z` | published on **production**, to everyone | release |
| Manual run (*Run workflow*) | the version you enter, `X.Y.Z` or `X.Y.Z-rc.N` | nothing | nothing |

Nothing waits for a person in the Play Console: a candidate reaches internal testers, and a
release reaches every user once Play's review passes. Pushing the tag is the decision to
ship.

To cut a candidate, then ship the commit it was built from:

```sh
git fetch origin
git tag -a -m v1.1.0-rc.1 v1.1.0-rc.1 <commit on origin/main>
git push origin v1.1.0-rc.1

git tag -a -m v1.1.0 v1.1.0 <same commit>
git push origin v1.1.0
```

The workflow's first job, the gate (`.github/scripts/release-gate.sh`), checks the run
against the repository before anything sees the signing secrets, and fails the run when:

- the tag's commit is not on `main` — tag after merging, never a pull request's branch;
- the tag no longer points at the commit CI passed on — a tag moved after its push;
- there is no such tag, or a branch has the same name: a branch called `v1.1.0` is not a
  release, and a name that is both is refused until the branch is deleted;
- the run that triggered it is not `.github/workflows/ci.yml` in this repository;
- a manual run's version is not `X.Y.Z` or `X.Y.Z-rc.N`.

`make release-gate-check` runs it against each of those cases in a throwaway repository.

A release does not need a candidate before it. Tag in increasing order: Play refuses a
version code lower than one it has already seen, so `v1.0.1-rc.1` can follow `v1.0.0`, but
`v1.0.0-rc.4` cannot.

## Version code

`MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + N`, where `N` is the candidate number
and 99 for the release, so `1.0.0-rc.1` → `1000001`, `1.0.0` → `1000099`. It is derived in
`app/build.gradle.kts` from `-PreleaseVersion` and never edited by hand. A local build
without the property is `0.0.0-dev` / `1`. Minor and patch stay below 100, and candidates
run from `rc.1` to `rc.98`; the build fails on a tag outside that.

## Secrets

These are set on the `release` environment (*Settings → Environments*), not on the
repository:

| Secret | Content |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 upload.jks` |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | the upload key alias |
| `KEY_PASSWORD` | the upload key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | the service account's JSON key, with release rights on `app.titan.gym` |

If the environment has required reviewers, every pushed release tag and every manual run
waits for an approval before it is built. A deployment-branch policy on the environment does
not help here: the workflow runs on `workflow_run`, which GitHub treats as running on `main`
whatever the tag. What the environment's settings were when last read is in
`security-audit.md`.

## Signing certificate

The checksum published beside each APK on the GitHub release proves the download is intact,
not who built it. What proves that is the certificate the APK is signed with, and its
fingerprint goes here so anyone can compare.

| Distributed by | Signed with | SHA-256 fingerprint |
| --- | --- | --- |
| GitHub releases (`titan-X.Y.Z.apk`) | the upload key | *not published yet* |
| Google Play | Google's app signing key | *not published yet* |

The APK on the GitHub release is the one the workflow signed with the upload key. Its
fingerprint, from a release APK or from the keystore itself (the two must agree):

```sh
# from build-tools; prints "Signer #1 certificate SHA-256 digest: …"
apksigner verify --print-certs titan-X.Y.Z.apk

# from the keystore; prints "SHA256: …" under "Certificate fingerprints"
keytool -list -v -keystore upload.jks -alias <KEY_ALIAS>
```

`keytool -printcert -jarfile` does not work for this app: with `minSdk` 26 the APK carries
only APK Signature Scheme v2, not the JAR signature keytool reads, and keytool answers "Not a
signed jar file".
`apksigner` prints the digest as lowercase hex without separators and `keytool` as uppercase
pairs separated by colons; compare them ignoring case and colons.

Play re-signs what it delivers with the app signing key it holds, so the APK a phone installs
from Play has a different certificate. Its fingerprint is under *App integrity → App
signing* in the Play Console.

A user checks an APK they downloaded the same way, with `apksigner verify --print-certs`.

## When an upload fails

Fix the cause, then re-run the failed workflow from the Actions tab. The tag stays where it
is. A secret missing from the environment fails the run before the build, with the name of
the secret.

## First upload

Play only accepts API uploads for an app that already exists in the Console, and only
accepts drafts until the app's first release is out. Create `app.titan.gym` there and
publish the first bundle by hand, built with `keystore.properties` filled in:

```sh
./gradlew :app:bundleRelease -PreleaseVersion=1.0.0-rc.1
```

Do not push a tag for that version: the workflow would build it again and Play would refuse
the duplicate version code. Start tagging from the next one, `v1.0.0-rc.2`.
