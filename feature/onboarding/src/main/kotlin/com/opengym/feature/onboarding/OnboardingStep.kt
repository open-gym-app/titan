package com.opengym.feature.onboarding

/**
 * The questions first launch asks, in the order it asks them.
 *
 * **Five, and the count is the design.** Onboarding length is the main reason a first-run
 * flow is abandoned, so every step here has to earn its place: a greeting that says what the
 * app is, the two answers the next-load engine cannot suggest without, the one preference that
 * changes how every later screen reads, and a closing step that shows the answers back.
 * Anything else the app could ask — a body weight, a training split, notification permission —
 * is a question the user can answer better once they have seen the app, and belongs on the
 * screen that needs it.
 *
 * The declaration order *is* the flow: [number], [next] and [previous] are all derived from
 * the ordinal, so re-ordering the constants re-orders the flow and there is no second list to
 * keep in step.
 */
enum class OnboardingStep {

    /** What this app is, before it asks for anything. */
    WELCOME,

    /** The training goal — the rep range a next-load suggestion targets (#M7-15). Shown in settings too. */
    GOAL,

    /**
     * The fitness level — how fast a next-load suggestion progresses (#M7-15), asked as how long
     * the user has trained. Right after the goal because the two are one decision to the
     * engine, and before the units because a skipped level still leaves a working app where a
     * skipped unit system leaves every number in the wrong one.
     */
    LEVEL,

    /** Kilograms or pounds — the one answer that changes every number the user later reads. */
    UNITS,

    /** The answers, read back, and the way out into the app. */
    READY,
    ;

    /** Which step this is, counting from one, for `STEP 2 OF 5`. */
    val number: Int get() = ordinal + 1

    /** The last step is the one that finishes onboarding rather than advancing it. */
    val isLast: Boolean get() = this == entries.last()

    /**
     * Whether the step can be skipped.
     *
     * Every step but the last, and the last one only because there is nothing left to skip:
     * its action *is* the way out. Skipping a step applies that step's documented default —
     * see `OnboardingViewModel.skip` — so a user who skips everything ends up with a fully
     * set-up app rather than with preferences that were never written.
     */
    val isSkippable: Boolean get() = !isLast

    /** The next step, or **null** on the last one. */
    fun next(): OnboardingStep? = entries.getOrNull(ordinal + 1)

    /** The previous step, or **null** on the first — which is where the back gesture stops. */
    fun previous(): OnboardingStep? = entries.getOrNull(ordinal - 1)

    companion object {

        /** How many steps the flow has, for the counter and the progress rail. */
        val COUNT: Int = entries.size

        /** Where the flow opens. */
        val FIRST: OnboardingStep = entries.first()
    }
}
