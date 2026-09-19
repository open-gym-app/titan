package com.opengym.buildlogic

/**
 * The version a build of `:app` carries, derived from a git tag and from nothing else.
 *
 * A release is a pushed `v<MAJOR>.<MINOR>.<PATCH>` tag, optionally suffixed `-rc.<N>` for a
 * release candidate (see `release/RELEASE.md`). `.github/workflows/release.yml` strips the
 * `v` and hands the rest to Gradle as `-PreleaseVersion=`; `app/build.gradle.kts` turns that
 * into the two numbers Android wants through [parse]. Nothing is edited by hand, so the
 * version in an APK and the tag it was built from cannot disagree, and two builds of the
 * same tag get the same `versionCode` — which is what lets a build be reproduced.
 *
 * Without the property (every local build, and CI's own `assembleRelease`) the version is
 * [DEV]: `0.0.0-dev` with a `versionCode` of `1`, below every release's (the first tag is
 * `v1.0.0`), so a development build never counts as an upgrade over a release on the same
 * device.
 */
data class ReleaseVersion(
    /** What `versionName` shows: the tag without its `v`, suffix included. */
    val name: String,
    /**
     * `MAJOR * 10000 + MINOR * 100 + PATCH`. Monotonic as long as `MINOR` and `PATCH` stay
     * below 100, which the parser enforces — Play refuses a `versionCode` it has already
     * seen and never lets one go backwards, so an overflow here would be a release nobody
     * could ship. An `-rc.N` suffix does not change it: the candidate and the release it
     * precedes are the same code, which is why a candidate never goes to Play.
     */
    val code: Int,
) {
    /** True for a `-rc.N` tag: published as a pre-release, never uploaded to Play. */
    val isReleaseCandidate: Boolean
        get() = name.contains("-rc.")

    companion object {
        /** The version of a build that was not asked for one. */
        val DEV = ReleaseVersion(name = "0.0.0-dev", code = 1)

        private const val MAJOR_WEIGHT = 10_000
        private const val MINOR_WEIGHT = 100
        private const val COMPONENT_LIMIT = 100

        private val TAG = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-rc\.([1-9]\d*))?""")

        /** The three numeric groups of [TAG]; the fourth is the candidate number. */
        private const val COMPONENTS = 3

        /**
         * Parses the value of `-PreleaseVersion=`, or returns [DEV] for `null`.
         *
         * Fails on anything that is not `MAJOR.MINOR.PATCH` or `MAJOR.MINOR.PATCH-rc.N` with
         * every component below 100 and no leading zero: a tag the rule does not cover is a
         * typo until proven otherwise, and the place to find out is configuration, not a
         * Play upload rejected for a `versionCode` that already exists.
         */
        fun parse(property: String?): ReleaseVersion {
            if (property == null) return DEV
            val match = TAG.matchEntire(property)
            requireNotNull(match) {
                "releaseVersion '$property' is not MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-rc.N " +
                    "(no 'v' prefix, no leading zeros); see release/RELEASE.md"
            }
            val (major, minor, patch) = match.destructured.toList().take(COMPONENTS).map(String::toInt)
            require(minor < COMPONENT_LIMIT && patch < COMPONENT_LIMIT) {
                "releaseVersion '$property': MINOR and PATCH must stay below $COMPONENT_LIMIT, " +
                    "or versionCode = MAJOR * $MAJOR_WEIGHT + MINOR * $MINOR_WEIGHT + PATCH stops " +
                    "increasing with the version"
            }
            return ReleaseVersion(
                name = property,
                code = major * MAJOR_WEIGHT + minor * MINOR_WEIGHT + patch,
            )
        }
    }
}
