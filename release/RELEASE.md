# Releasing

`.github/workflows/release.yml` does every upload after the first one.

## The flow

Every release, candidate or not, is a tag you push. Merging to `main` publishes nothing.

| Trigger | Version | Google Play | GitHub |
| --- | --- | --- | --- |
| CI passes on a pushed `vX.Y.Z-rc.N` tag | `X.Y.Z-rc.N` | published on **internal** | pre-release |
| CI passes on a pushed `vX.Y.Z` tag | `X.Y.Z` | published on **production**, to everyone | release |
| Manual run (*Run workflow*) | the version you enter | nothing | nothing |

Nothing waits for a person in the Play Console: a candidate reaches internal testers, and a
release reaches every user once Play's review passes. Pushing the tag is the decision to
ship.

To cut a candidate, then ship the commit it was built from:

```sh
git tag v1.1.0-rc.1 <commit>
git push origin v1.1.0-rc.1

git tag v1.1.0 <same commit>
git push origin v1.1.0
```

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
waits for an approval before it is built.

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
