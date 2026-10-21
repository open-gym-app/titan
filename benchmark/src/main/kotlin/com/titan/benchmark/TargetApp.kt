package com.titan.benchmark

/**
 * The app under test, as this module can address it: a package name and a handful of resource
 * ids.
 *
 * ### Why these are literals and not imports
 *
 * `:benchmark` does not link against the app. It is a separate APK, in a separate process,
 * driving a release-like build through UiAutomator — which sees an accessibility node tree and
 * nothing else. The strings below are the same ones `core/ui/.../test/TitanTestTags.kt`
 * writes into that tree via `Modifier.testTag`, restated here because importing them would
 * mean putting `:core:ui` and the whole Compose stack on this module's classpath for six
 * constants.
 *
 * A drift between the two lists is not silent: the benchmark fails at "no node matched", which
 * is the loudest failure this module has.
 *
 * ### The tags, and not the text
 *
 * The app ships in four languages. `By.text("SESSIONS")` finds the tab on an English phone and
 * nothing at all on a French one, so every handle here is a tag.
 */
internal object TargetApp {

    /**
     * `:app`'s `applicationId`. The `benchmark` build type deliberately adds no
     * `applicationIdSuffix` — see `app/build.gradle.kts` — so this is the same name the
     * shipping build carries.
     */
    const val PACKAGE_NAME = "app.titan.gym"

    /** The seeding provider's authority. Mirrors `BenchmarkSeedProvider.AUTHORITY_SUFFIX`. */
    const val SEED_AUTHORITY = "$PACKAGE_NAME.benchmark.seed"

    /** Mirrors `BenchmarkSeedProvider.METHOD_SEED`. */
    const val SEED_METHOD = "seed"

    /** Mirrors `BenchmarkSeedProvider.RESULT_SET_COUNT`. */
    const val SEED_RESULT_SET_COUNT = "setCount"

    /** `TitanTestTags.bottomBarItem(TopLevelDestination.SESSIONS.name)`. */
    const val SESSIONS_TAB = "bottomBar:SESSIONS"

    /** `TitanTestTags.bottomBarItem(TopLevelDestination.STATS.name)`. */
    const val STATS_TAB = "bottomBar:STATS"

    /** `TitanTestTags.SESSIONS_HISTORY_LIST`. */
    const val SESSIONS_HISTORY_LIST = "sessions:historyList"

    /** `TitanTestTags.STATS_OVERVIEW`. */
    const val STATS_OVERVIEW = "stats:overview"

    /** `TitanTestTags.STATS_VOLUME_HERO`. */
    const val STATS_VOLUME_HERO = "stats:volumeHero"

    /** `TitanTestTags.STATS_EXPLORE_EXERCISE`. */
    const val STATS_EXPLORE_EXERCISE = "stats:explore:exercise"

    /** `TitanTestTags.EXERCISE_CHOOSER`. */
    const val EXERCISE_CHOOSER = "exercise:chooser"

    /** `TitanTestTags.EXERCISE_PROGRESSION_CHART`. */
    const val EXERCISE_PROGRESSION_CHART = "exercise:progressionChart"

    /**
     * How the seeded catalogue names its lifts — `Exercise 0` through `Exercise 19`, from
     * `WorkoutHistoryFixture.seedExercises`.
     *
     * The one place this module matches on text rather than on a tag, and it is safe for the
     * reason the tab labels are not: this is *data the fixture wrote*, not a string resource,
     * so it reads the same in every language.
     */
    const val SEEDED_EXERCISE_PREFIX = "Exercise "

    /**
     * How long to wait for a screen before giving up.
     *
     * Generous on purpose. This is not a budget — the budgets are the frame timings the
     * benchmark reports — it is the point at which "the app is slow" becomes "the app is
     * broken and the run should say so rather than hang".
     */
    const val UI_TIMEOUT_MS = 10_000L
}
