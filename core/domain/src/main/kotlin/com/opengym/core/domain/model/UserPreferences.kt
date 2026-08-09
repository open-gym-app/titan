package com.opengym.core.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every setting the user can change, as one value.
 *
 * One object rather than six independent reads, because almost every consumer wants more
 * than one of them and a screen that collected six flows would have six chances to render
 * a half-applied change. The repository still writes them one at a time — see
 * [com.opengym.core.domain.repository.UserPreferencesRepository] — so the grouping costs
 * nothing on the write side.
 *
 * ### Defaults are part of the contract
 *
 * Every field has one, and the whole object is constructible with none supplied. That is
 * what a fresh install reads, and it is also what a stored value falls back to when it
 * cannot be understood: the store holds settings, not records, so an unreadable one must
 * degrade to the default rather than crash the app that is trying to draw a screen. The
 * data layer's mapper owes that; this class only owes the values.
 *
 * ### What is *not* here
 *
 * Nothing that a screen can derive. [unitSystem] is the clearest case: it decides how a
 * weight is *rendered* and never what is stored, so there is no second, imperial copy of
 * anything — see [WorkoutSet] for the canonical units and #M4-17 for the formatters that
 * read this.
 */
data class UserPreferences(
    /** Whether weights and distances are shown in metric or imperial units. */
    val unitSystem: UnitSystem = UnitSystem.METRIC,

    /** Which colour scheme to render in. */
    val theme: ThemePreference = ThemePreference.SYSTEM,

    /**
     * The rest a set gets when nothing more specific applies, seeding
     * [com.opengym.core.domain.timer.RestTimerController.start].
     *
     * The *default*, not the duration: an exercise carrying its own `defaultRest`
     * overrides it, and the rest timer sheet (#M4-24) may adjust a running countdown
     * without this changing. Must be a positive, finite, whole number of seconds.
     */
    val defaultRestDuration: Duration = DEFAULT_REST_DURATION,

    /**
     * The empty barbell the plate calculator (#M4-21) works up from, **in milligrams**,
     * matching [WorkoutSet.weight].
     *
     * Milligrams for a value the user will type as "20" is a poor fit on its own, and it
     * is stored this way anyway: it is added to and subtracted from set weights, and a
     * second unit for the same quantity is how a rounding discrepancy gets in. Must be
     * positive — a bar of no weight is not a preference, it is a missing answer.
     */
    val barWeight: Long = DEFAULT_BAR_WEIGHT,

    /** What the user says they are training for. Recorded, not yet acted on — see [TrainingGoal]. */
    val trainingGoal: TrainingGoal = TrainingGoal.STRENGTH,

    /**
     * Whether the user has been through onboarding.
     *
     * The flag that makes first launch route to onboarding *exactly once*: it is the
     * only durable record that the questions were asked, so nothing else — an empty
     * session list, an unset [trainingGoal] — should be read as a proxy for it. A user
     * who skips every question has still completed onboarding.
     */
    val onboardingCompleted: Boolean = false,
) {
    init {
        // Whole seconds, not merely positive and finite. A rest is set in seconds, shown
        // in seconds and stored in seconds, so 500 ms is not a rest anybody can express —
        // and admitting it here would make this class disagree with the repository that
        // has to persist it: `setDefaultRestDuration` refuses anything under a second, so
        // a value this constructor blessed would throw on its way back to the store. The
        // model is the one that gets to say what a rest is; the invariant belongs here.
        require(defaultRestDuration.isFinite() && defaultRestDuration >= 1.seconds) {
            "the default rest duration must be finite and at least one second, was $defaultRestDuration"
        }
        require(defaultRestDuration.inWholeSeconds.seconds == defaultRestDuration) {
            "the default rest duration must be a whole number of seconds, was $defaultRestDuration"
        }
        require(barWeight > 0) { "the bar weight must be positive, was $barWeight mg" }
    }

    companion object {

        /**
         * The rest a set gets before the user has ever said otherwise.
         *
         * This is *the* 90 seconds — it used to be a constant on `RestTimerController`,
         * which meant a caller that forgot to read the preferences silently got the old
         * default and the setting appeared not to work. The controller now takes a
         * duration and has no opinion about it, so this is the only place the number
         * lives.
         */
        val DEFAULT_REST_DURATION: Duration = 90.seconds

        /** An Olympic barbell, 20 kg, in milligrams. */
        const val DEFAULT_BAR_WEIGHT: Long = 20_000_000L
    }
}
