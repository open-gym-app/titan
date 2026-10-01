package com.opengym.core.domain.model

/**
 * What to do before the workout and what to do after it — the answer
 * [com.opengym.core.domain.warmup.WarmupPlanner] gives for one session (#214).
 *
 * Two halves in one value rather than two suggestions, because they are computed from the same
 * reading of the same session: the muscles it trains decide both lists, and answering them
 * separately would be that reading done twice with two chances to disagree about which muscles
 * those were.
 *
 * ### Nothing here has been added to anything
 *
 * This is a proposal. The exercises named in it are catalogue rows, not `session_exercise`
 * ones, and the ramp-up sets are [PlannedSet]s that exist only in this object until the user
 * accepts them. A lifter who wants no warm-up never sees one in their log — see
 * [com.opengym.core.domain.usecase.AddSessionExercisesUseCase], which is what accepting runs.
 *
 * @property warmup the dynamic movements to do first, in the order suggested. Two or three of
 *   them, or empty where the session trains nothing the catalogue has a match for.
 * @property rampUp the ramp-up sets for [rampUpExerciseId], lightest first, every one of them
 *   [SetType.WARMUP]. Empty for a session with no loaded compound lift to ramp into —
 *   bodyweight work, cardio, or a workout whose first sets have no weight planned yet.
 * @property rampUpExerciseId the catalogue exercise [rampUp] belongs to, or **null** when there
 *   are no ramp-up sets. Held beside them rather than inside each set, because a [PlannedSet]
 *   has no exercise: it is a row of the table the exercise already owns. For display only —
 *   see [rampUpSessionExerciseId] for the row the sets are written into.
 * @property rampUpSessionExerciseId the `session_exercise` row [rampUp] is written into, or
 *   **null** when there are no ramp-up sets. The same catalogue exercise can occupy more than
 *   one row of a session (two bench slots either side of an accessory), and [rampUpExerciseId]
 *   alone cannot tell those rows apart — this is the one that can.
 * @property cooldown the static stretches to do afterwards, in the order suggested.
 * @property includeGeneralNote whether the card should also say "five minutes of easy cardio".
 *   True for a session spread over more than [GENERAL_NOTE_MUSCLE_GROUPS] muscle groups, where
 *   no two or three movements can cover everything that is about to be worked and raising the
 *   whole body's temperature is the honest advice.
 */
data class WarmupSuggestion(
    val warmup: List<MobilityExercise> = emptyList(),
    val rampUp: List<PlannedSet> = emptyList(),
    val rampUpExerciseId: Long? = null,
    val rampUpSessionExerciseId: Long? = null,
    val cooldown: List<MobilityExercise> = emptyList(),
    val includeGeneralNote: Boolean = false,
) {
    init {
        require(rampUp.isEmpty() == (rampUpExerciseId == null)) {
            "ramp-up sets and the exercise they belong to travel together, had " +
                "${rampUp.size} set(s) and exercise $rampUpExerciseId"
        }
        require(rampUp.isEmpty() == (rampUpSessionExerciseId == null)) {
            "ramp-up sets and the session row they belong to travel together, had " +
                "${rampUp.size} set(s) and session exercise $rampUpSessionExerciseId"
        }
        require(rampUp.all { it.setType == SetType.WARMUP }) {
            "every ramp-up set is a warm-up set, had ${rampUp.map(PlannedSet::setType)}"
        }
    }

    /**
     * Whether there is a warm-up worth putting on the screen.
     *
     * The **movements** decide it, and neither [rampUp] nor [includeGeneralNote] can carry a card
     * on its own. A card is a list of things to do with one action that adds them, so a card with
     * nothing in the list is a card whose action is inert — and two ramp-up sets under a heading,
     * with no way to take them, is worse than no card at all. In practice it costs almost
     * nothing: the seed's dynamic movements are the prime movers of twelve of the seventeen
     * muscle groups, and the secondary links a session is matched on widen that further, so a
     * workout with nothing to warm it up with is a rare one.
     */
    val hasWarmup: Boolean get() = warmup.isNotEmpty()

    /** Whether there is anything at all to propose after it. */
    val hasCooldown: Boolean get() = cooldown.isNotEmpty()

    companion object {

        /**
         * How many muscle groups a session has to reach before the general note is worth adding.
         *
         * Three, i.e. "more than two". A push day is chest, shoulders and triceps and gets it; a
         * bench-and-rows session is two and does not, because two or three targeted movements
         * genuinely do cover it and a line about cardio on top would be advice nobody reads.
         */
        const val GENERAL_NOTE_MUSCLE_GROUPS: Int = 2
    }
}
