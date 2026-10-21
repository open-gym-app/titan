package com.titan.buildlogic

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
 * [DEV]: `0.0.0-dev` with a `versionCode` of `1`, below every tag's, so a development build
 * never counts as an upgrade over a release on the same device.
 */
data class ReleaseVersion(
    /** What `versionName` shows: the tag without its `v`, suffix included. */
    val name: String,
    /**
     * `MAJOR * 1000000 + MINOR * 10000 + PATCH * 100 + N`, where `N` is the candidate number
     * for `-rc.N` and [RELEASE_SLOT] for the release itself. Every candidate goes to Play's
     * internal track and Play refuses a `versionCode` it has already seen, so a candidate
     * needs a code of its own, below the release it precedes and above the release before:
     * `1.0.0-rc.1 < 1.0.0-rc.2 < 1.0.0 < 1.0.1-rc.1`. Monotonic as long as `MINOR` and `PATCH`
     * stay below 100 and `N` below [RELEASE_SLOT], which the parser enforces.
     */
    val code: Int,
    /**
     * The [code] of the release this version is or precedes: [code] itself for a release, the
     * release's for a candidate. The changelog is keyed by it, so a candidate shows the notes
     * of the release it is a candidate for.
     */
    val releaseCode: Int = code,
) {
    /** True for a `-rc.N` tag: a GitHub pre-release, on Play's internal track only. */
    val isReleaseCandidate: Boolean
        get() = name.contains("-rc.")

    companion object {
        /** The version of a build that was not asked for one. */
        val DEV = ReleaseVersion(name = "0.0.0-dev", code = 1)

        private const val MAJOR_WEIGHT = 1_000_000
        private const val MINOR_WEIGHT = 10_000
        private const val PATCH_WEIGHT = 100
        private const val COMPONENT_LIMIT = 100

        /** The last two digits of a release's code; a candidate's are its number, below it. */
        const val RELEASE_SLOT = 99

        private val TAG = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-rc\.([1-9]\d*))?""")

        /** The three numeric groups of [TAG]; the fourth is the candidate number. */
        private const val COMPONENTS = 3

        /**
         * Parses the value of `-PreleaseVersion=`, or returns [DEV] for `null`.
         *
         * Fails on anything that is not `MAJOR.MINOR.PATCH` or `MAJOR.MINOR.PATCH-rc.N` with
         * `MINOR` and `PATCH` below 100, `N` below [RELEASE_SLOT] and no leading zero: a tag the
         * rule does not cover is a typo until proven otherwise, and the place to find out is
         * configuration, not a Play upload rejected for a `versionCode` that already exists.
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
                    "or versionCode stops increasing with the version"
            }
            val candidate = match.groupValues[COMPONENTS + 1].toIntOrNull()
            require(candidate == null || candidate < RELEASE_SLOT) {
                "releaseVersion '$property': a version has at most ${RELEASE_SLOT - 1} candidates, " +
                    "the next code is the release's own"
            }
            val base = major * MAJOR_WEIGHT + minor * MINOR_WEIGHT + patch * PATCH_WEIGHT
            return ReleaseVersion(
                name = property,
                code = base + (candidate ?: RELEASE_SLOT),
                releaseCode = base + RELEASE_SLOT,
            )
        }
    }
}
