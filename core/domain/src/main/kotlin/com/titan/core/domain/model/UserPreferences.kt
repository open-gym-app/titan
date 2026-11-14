package com.titan.core.domain.model

import com.titan.core.domain.gamification.StreakCalculator
import com.titan.core.domain.notification.NotificationPreferences
import com.titan.core.domain.notification.ReminderPreferences
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.validation.SetValidator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Every setting the user can change, as one value.
 *
 * One object rather than six independent reads, because almost every consumer wants more
 * than one of them and a screen that collected six flows would have six chances to render
 * a half-applied change. The repository still writes them one at a time — see
 * [com.titan.core.domain.repository.UserPreferencesRepository] — so the grouping costs
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
     * [com.titan.core.domain.timer.RestTimerController.start].
     *
     * The *default*, not the duration: an exercise carrying its own `defaultRest`
     * overrides it, and the rest timer sheet (#M4-24) may adjust a running countdown
     * without this changing. Must be a whole number of seconds, from one to
     * [MAX_REST_DURATION].
     */
    val defaultRestDuration: Duration = DEFAULT_REST_DURATION,

    /**
     * The empty barbell the plate calculator (#M4-21) works up from, **in milligrams**,
     * matching [WorkoutSet.weight].
     *
     * Milligrams for a value the user will type as "20" is a poor fit on its own, and it
     * is stored this way anyway: it is added to and subtracted from set weights, and a
     * second unit for the same quantity is how a rounding discrepancy gets in. Must be
     * positive — a bar of no weight is not a preference, it is a missing answer — and at most
     * [MAX_BAR_WEIGHT].
     */
    val barWeight: Long = DEFAULT_BAR_WEIGHT,

    /**
     * The plates the user can reach, or **null** while they have never said.
     *
     * The one nullable setting, and the null is load-bearing rather than a missing default: what
     * a fresh install should offer is *the standard rack for the unit system they lift in*, and
     * that is not a constant, it is a function of [unitSystem]. Storing metric plates as the
     * default would hand an American user a 1.25 kg change plate; storing nothing and deriving
     * on read means switching to imperial before ever opening the inventory editor switches the
     * rack with it. Read it through [plates], which does that derivation.
     *
     * Once the user edits the rack it stops tracking [unitSystem], which is correct: plates are
     * physical objects, and somebody who has said what is in their gym has not changed what is
     * in it by asking to read weights in pounds.
     */
    val plateInventory: PlateInventory? = null,

    /** What the user says they are training for — the rep range a next-load suggestion targets, see [TrainingGoal]. */
    val trainingGoal: TrainingGoal = TrainingGoal.STRENGTH,

    /**
     * How experienced a lifter the user says they are — how fast a next-load suggestion
     * progresses, see [FitnessLevel].
     *
     * Defaults to the most cautious band, deliberately: a user who skipped the question gets
     * suggestions that go up slowly rather than ones that assume three years under the bar.
     */
    val fitnessLevel: FitnessLevel = FitnessLevel.BEGINNER,

    /**
     * The rep range a next-load suggestion targets when [trainingGoal] is [TrainingGoal.CUSTOM].
     *
     * Stored whatever the goal is, and read only under CUSTOM — see [repRange], which is what
     * every reader wants. Keeping it while the goal is something else is what lets a user try
     * HYPERTROPHY for a month and come back to the range they typed. The three named goals
     * carry their ranges as constants on [RepRange], not here: those are the app's, this is
     * the user's.
     */
    val customRepRange: RepRange = RepRange.DEFAULT_CUSTOM,

    /**
     * How much weight a next-load suggestion adds when [trainingGoal] is [TrainingGoal.CUSTOM],
     * **in milligrams**, or **null** while the user has never said.
     *
     * Nullable for the reason [plateInventory] is: the right default is not a constant, it is
     * the smallest step the user's own rack can make — twice its smallest plate — and that
     * follows the rack and the unit system until the user overrides it. The next-load engine
     * derives it on read; the settings dialog seeds its field with the same derivation. Must
     * be positive when set — an increment of nothing is a missing answer, not a preference —
     * and at most [MAX_CUSTOM_INCREMENT].
     */
    val customIncrement: Long? = null,

    /**
     * Which formula estimates a one-rep max from a set that was not one — see
     * [OneRepMaxFormula] for the tradeoff between the two, and
     * [com.titan.core.domain.pr.OneRepMaxCalculator] for where this is read.
     */
    val oneRepMaxFormula: OneRepMaxFormula = OneRepMaxFormula.EPLEY,

    /**
     * Whether the active session offers a warm-up and a cool-down (#214).
     *
     * **On by default**, which is the safe direction for a suggestion that adds nothing until
     * it is accepted: the cards propose, and a lifter who wants no warm-up never sees one in
     * their log. Off hides both cards outright rather than emptying them —
     * [com.titan.core.domain.usecase.SuggestWarmupUseCase] answers with an empty suggestion,
     * and a screen with nothing to draw draws nothing.
     */
    val warmupSuggestions: Boolean = true,

    /**
     * Whether the rest timer's sheet comes up on its own when a rest starts after a set, or
     * comes back up when a workout is reopened mid-rest.
     *
     * On by default, which is what the screen has always done: mid-rest the countdown is the
     * only thing that matters. Off is for the lifter who finds a panel landing under their
     * thumb the moment a set is logged more in the way than useful. The rest still starts, and
     * the dock at the bottom of the workout carries its countdown. Only the sheet stays down
     * until they ask for it from the dock or the session clock. Tapping either of those raises
     * it whatever this says, because then the lifter asked for it.
     */
    val autoOpenRestTimer: Boolean = true,

    /**
     * How many workouts a week the user means to do — the goal a week has to meet to join the
     * streak. See [StreakCalculator], which is the only reader of it that decides anything.
     *
     * The user's own rather than a constant, because the right number is a property of their
     * programme: a three-day split that hits every planned session should not lose its streak
     * to a goal written for somebody training four times.
     */
    val weeklyWorkoutGoal: Int = StreakCalculator.DEFAULT_WEEKLY_GOAL,

    /**
     * Whether the user has been through onboarding.
     *
     * The flag that makes first launch route to onboarding *exactly once*: it is the
     * only durable record that the questions were asked, so nothing else — an empty
     * session list, an unset [trainingGoal] or [fitnessLevel] — should be read as a proxy for
     * it. A user who skips every question has still completed onboarding.
     */
    val onboardingCompleted: Boolean = false,

    /**
     * Which kinds of notification the user has agreed to — see [NotificationPreferences].
     *
     * Here rather than in a store of its own, because it is a setting like any other: the
     * settings screen renders it beside the theme and the unit system, `clear()` puts it back
     * to its default with everything else, and a second store would be a second thing to read
     * before the first frame. The nesting is what keeps four flags from being four more
     * top-level fields on a class that already has eight.
     */
    val notifications: NotificationPreferences = NotificationPreferences(),

    /**
     * When the workout reminder fires, if the user has switched it on — see
     * [ReminderPreferences].
     *
     * Beside [notifications] rather than inside it, because it answers a different question:
     * [notifications] says *whether* each kind may be posted, and this says *when* one
     * particular kind is due. Folding the schedule into the set of enabled kinds would give
     * one of the four a shape the other three do not have.
     */
    val reminders: ReminderPreferences = ReminderPreferences(),
) {
    init {
        // Whole seconds, not merely positive and finite. A rest is set in seconds, shown
        // in seconds and stored in seconds, so 500 ms is not a rest anybody can express —
        // and admitting it here would make this class disagree with the repository that
        // has to persist it: `setDefaultRestDuration` refuses anything under a second, so
        // a value this constructor blessed would throw on its way back to the store. The
        // model is the one that gets to say what a rest is; the invariant belongs here.
        require(defaultRestDuration >= 1.seconds && defaultRestDuration <= MAX_REST_DURATION) {
            "the default rest duration must be between one second and $MAX_REST_DURATION, was $defaultRestDuration"
        }
        require(defaultRestDuration.inWholeSeconds.seconds == defaultRestDuration) {
            "the default rest duration must be a whole number of seconds, was $defaultRestDuration"
        }
        require(barWeight in 1..MAX_BAR_WEIGHT) { "the bar weight must be in 1..$MAX_BAR_WEIGHT mg, was $barWeight" }
        require(customIncrement == null || customIncrement in 1..MAX_CUSTOM_INCREMENT) {
            "the custom increment must be in 1..$MAX_CUSTOM_INCREMENT mg or absent, was $customIncrement"
        }
        require(weeklyWorkoutGoal in StreakCalculator.WEEKLY_GOAL_RANGE) {
            "the weekly workout goal must be in ${StreakCalculator.WEEKLY_GOAL_RANGE}, was $weeklyWorkoutGoal"
        }
    }

    /**
     * The rep range the next-load engine targets: the constant for the goal, or the user's own
     * range under [TrainingGoal.CUSTOM].
     *
     * The one place the goal is turned into numbers. A reader that wrote its own `when` over
     * [TrainingGoal] would be a second copy of this table, and the two would disagree the day
     * one of them is tuned.
     */
    val repRange: RepRange
        get() = when (trainingGoal) {
            TrainingGoal.STRENGTH -> RepRange.STRENGTH
            TrainingGoal.HYPERTROPHY -> RepRange.HYPERTROPHY
            TrainingGoal.ENDURANCE -> RepRange.ENDURANCE
            TrainingGoal.CUSTOM -> customRepRange
        }

    /**
     * The rack to load from: what the user said they own, or the standard set for [unitSystem].
     *
     * Every reader wants this rather than [plateInventory] — the plate calculator, and the
     * next-load engine (#M7-15), which takes its step from the rack's smallest plate. The
     * nullable field is for the *editor*, which is the one caller that needs to tell "I have
     * not been asked" from "this is my rack".
     */
    val plates: PlateInventory get() = plateInventory ?: PlateInventory.defaultFor(unitSystem)

    companion object {

        /**
         * The rest a set gets before the user has ever said otherwise.
         *
         * Three minutes: the rest a working set of a compound lift actually takes, and so
         * the one a template's estimate has to price to come out near the session it
         * becomes. A shorter default suits accessories and undersells everything else;
         * an exercise that wants less carries its own `defaultRest`.
         *
         * It used to be a constant on `RestTimerController`, which meant a caller that
         * forgot to read the preferences silently got the old default and the setting
         * appeared not to work. The controller now takes a duration and has no opinion
         * about it, so this is the only place the number lives.
         */
        val DEFAULT_REST_DURATION: Duration = 180.seconds

        /** An Olympic barbell, 20 kg, in milligrams. */
        const val DEFAULT_BAR_WEIGHT: Long = 20_000_000L

        /**
         * The longest [defaultRestDuration]: one day.
         *
         * Far past any rest between two sets, and short enough that every sum a rest takes part
         * in stays finite. Without it, a rest read from a hand-edited backup could be large
         * enough to saturate to [Duration.INFINITE] once a template's estimate or the rest
         * timer added to it, and the subtraction that follows throws.
         */
        val MAX_REST_DURATION: Duration = 1.days

        /**
         * The heaviest [barWeight], in milligrams: `SetValidator.WEIGHT_MAX_MG`, ten tonnes.
         *
         * Nothing heavier is a set the app can log, so nothing heavier is a bar anybody loads,
         * and the bound keeps the plate calculator's arithmetic on a bar and a set weight well
         * inside a `Long`.
         */
        const val MAX_BAR_WEIGHT: Long = SetValidator.WEIGHT_MAX_MG

        /**
         * The largest [customIncrement], in milligrams, on [MAX_BAR_WEIGHT]'s argument: the
         * next-load engine adds it to a set weight, and both bounded by ten tonnes cannot
         * overflow.
         */
        const val MAX_CUSTOM_INCREMENT: Long = SetValidator.WEIGHT_MAX_MG
    }
}
