package com.opengym.core.ui.test

/**
 * The handful of nodes an out-of-process test is allowed to address by name.
 *
 * ### Why these exist at all
 *
 * `:benchmark` drives a release-like build of the app through UiAutomator, from a different
 * process. There is no Compose test rule on that side of the APK boundary — no
 * `onNodeWithText`, no semantics tree — only the accessibility node tree, in which a Compose
 * `testTag` shows up as a *resource id* and only because `OpenGymApp` opts in with
 * `testTagsAsResourceId`.
 *
 * Matching on visible text was the alternative and is not one: the app ships in four languages
 * (#41, #184-#186), so `By.text("SESSIONS")` is a benchmark that passes on an English phone
 * and fails on a French one. A tag is the same string everywhere.
 *
 * ### The rules for adding one
 *
 * Sparingly, and only for a node an *external* driver has to find: a tab to press, a list to
 * fling, the thing whose appearance means the screen has finished. Compose tests inside the
 * app have the semantics tree and should keep using it — a tag added for a JVM test is a tag
 * that will outlive the test and mean nothing to the next reader.
 *
 * The values are `const`, so `:benchmark` could not import them without taking a dependency on
 * the whole Compose stack for four strings. It restates them in its own page objects instead,
 * and each side names the other; a drift breaks the benchmark loudly, at "no node matched".
 */
object OpenGymTestTags {

    /** A bottom-bar tab, by its `TopLevelDestination` name — `bottomBar:SESSIONS`. */
    fun bottomBarItem(destinationName: String): String = "bottomBar:$destinationName"

    /** The Sessions tab's history list. What a scroll benchmark flings. */
    const val SESSIONS_HISTORY_LIST = "sessions:historyList"

    /** The Stats hub's scrolling column: present as soon as the screen is composed. */
    const val STATS_OVERVIEW = "stats:overview"

    /** The volume hero. The Stats hub is *rendered* when this is on screen, not before. */
    const val STATS_VOLUME_HERO = "stats:volumeHero"

    /** The explore row that opens the exercise progression screen. */
    const val STATS_EXPLORE_EXERCISE = "stats:explore:exercise"

    /** The exercise chooser the progression screen opens on when no lift was named. */
    const val EXERCISE_CHOOSER = "exercise:chooser"

    /** The progression chart itself — the node whose arrival ends the render measurement. */
    const val EXERCISE_PROGRESSION_CHART = "exercise:progressionChart"

    /**
     * One day of the training calendar's month grid.
     *
     * The other external driver: the Accessibility Test Framework, which reads the same node
     * tree the benchmark does and is run over every preview by `:core:testing`. Seven columns
     * inside a card cannot each be 48dp wide on a phone, so a trained day's tap is the one
     * touch target the app accepts under Material's minimum — see docs/ACCESSIBILITY.md — and
     * this tag is how the check is told which finding that decision covers, and no other.
     * `:core:testing` restates the string, as `:benchmark` does with the tags above.
     */
    const val CALENDAR_DAY = "calendar:day"
}
