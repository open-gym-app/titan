# Releasing

`.github/workflows/release.yml` does every upload after the first one.

## The flow

| Trigger | Version | Google Play | GitHub |
| --- | --- | --- | --- |
| CI passes on a push to `main` | next patch after the last release, next free `-rc.N` | published on **internal** | pre-release |
| CI passes on a pushed `vX.Y.Z` tag | `X.Y.Z` | published on **production**, to everyone | release |
| Manual run (*Run workflow*) | what the next candidate would be | nothing | nothing |

Nothing waits for a person in the Play Console: a candidate reaches internal testers, and a
release tag reaches every user once Play's review passes. Pushing the tag is the decision to
ship.

To ship a candidate, tag the commit it was built from:

```sh
git tag v1.0.0 <commit>
git push origin v1.0.0
```

The candidate tags (`v1.0.0-rc.3`) already point at their commits. For a minor or major
release, tag `v1.1.0` or `v2.0.0` directly. Candidates always bump the patch, and the
version code stays monotonic either way.

## Version code

`MAJOR * 1_000_000 + MINOR * 10_000 + PATCH * 100 + N`, where `N` is the candidate number
and 99 for the release, so `1.0.0-rc.1` → `1000001`, `1.0.0` → `1000099`. It is derived in
`app/build.gradle.kts` from `-PreleaseVersion` and never edited by hand. A local build
without the property is `0.0.0-dev` / `1`.

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

If the environment has required reviewers, every push to `main` waits for an approval
before its candidate is built.

## When an upload fails

The candidate tag is pushed before the build, so a failed run burns its number and the
next push takes the next one. Nothing needs cleaning up.

If a release tag's upload fails, fix the cause, then re-run the failed workflow from the
Actions tab. The tag stays where it is.

## First upload

Play only accepts API uploads for an app that already exists in the Console, and only
accepts drafts until the app's first release is out. Create `app.titan.gym` there and
publish the first bundle by hand, built with `keystore.properties` filled in:

```sh
./gradlew :app:bundleRelease -PreleaseVersion=1.0.0-rc.1
```

Then push the `v1.0.0-rc.1` tag on the commit it was built from, so the workflow's next
candidate is `rc.2`. It publishes everything after that.
