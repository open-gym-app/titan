package com.opengym.core.domain.model

/**
 * What the next set should be, and why — the next-load engine's answer (#M7-15).
 *
 * A prescription rather than a prediction: it is what the user should *try*, given what they
 * did last time, and it is drawn beside the set they are about to log (#M7-16) so that a tap
 * fills the row in. Nothing here is a record of anything; the engine that produces it holds
 * no state and the app stores none of these.
 *
 * ### Weight is loadable or absent
 *
 * [weight] has been through the user's rack already — see
 * [com.opengym.core.domain.load.NextLoadEngine] for how — so a plate-loaded suggestion is one
 * the bar can be made to weigh, and a screen may draw it without a shortfall caveat. **Null**
 * means the movement carries no load: a press-up, a pull-up, a plank. Never zero, which would
 * read as an empty bar.
 *
 * ### It carries what it was read from
 *
 * [basis] is the performance the rule judged — the same one the `PREV` column draws beside it —
 * and [previousWeight] is the weight that performance was judged at. Both ride along because
 * the screens that show a suggestion (#M7-16) say *why* as well as *what*: the set row says
 * "+2.5 kg", which is a difference between two weights, and the exercise detail lists the sets
 * the target was derived from. Neither could be recomputed from the sets without restating the
 * engine's rule for which of them count, so the engine states it once and hands the answer on.
 *
 * @property weight the load to put on, in milligrams, or null for a bodyweight movement.
 * @property reps how many reps to aim for. Always at least one.
 * @property reason which rule produced this, for the one line the screen says under it.
 * @property previousWeight the heaviest weight the judged sets were lifted at, in milligrams —
 *   what [weight] moves from — or null when the performance carried no load.
 * @property basis the performance this was read from: the latest one, working sets in set order.
 */
data class LoadSuggestion(
    val weight: Long?,
    val reps: Int,
    val reason: SuggestionReason,
    val previousWeight: Long?,
    val basis: PastPerformance,
) {
    init {
        require(weight == null || weight > 0) { "a suggested weight must be positive or absent, was $weight mg" }
        require(reps >= 1) { "a suggestion must prescribe at least one rep, was $reps" }
        require(previousWeight == null || previousWeight > 0) {
            "the weight a suggestion moves from must be positive or absent, was $previousWeight mg"
        }
        // An increase is a step up from a weight to a heavier one — the engine has nothing to
        // add to a bodyweight movement, and a rack that cannot go heavier is an add-rep — so
        // a screen may always quote the step without a case for there being none.
        val heavier = weight != null && previousWeight != null && weight > previousWeight
        require(reason != SuggestionReason.INCREASE || heavier) {
            "an increase must move from a weight to a heavier one, was $previousWeight mg to $weight mg"
        }
    }

    /**
     * How far [weight] moves from [previousWeight], in milligrams: positive for an increase,
     * negative for a deload, zero for the same weight — and **null** when either side has no
     * load, since a bodyweight set does not move by a weight.
     */
    val weightChange: Long?
        get() = if (weight != null && previousWeight != null) weight - previousWeight else null
}

/**
 * Which branch of the progression rule a [LoadSuggestion] came out of.
 *
 * An enum rather than a sentence, because the sentence is the screen's to say and to
 * translate — `:core:domain` has no string resources. Each constant is one line on screen, and
 * the doc on each is that line's meaning rather than its wording.
 */
enum class SuggestionReason {

    /**
     * Every working set last time reached the top of the range, so the weight goes up by the
     * increment and the reps drop back to the bottom of the range. *"You hit the top of the
     * range — add weight."* Always carries both weights, the new one the heavier, so
     * [LoadSuggestion.weightChange] is a positive step.
     */
    INCREASE,

    /**
     * Last time's sets landed inside the range, so the weight stays and the target is one rep
     * more than the lowest set got. *"Same weight, one more rep."* Also every bodyweight
     * suggestion that is not a deload: with no load to add, more reps is the only way up.
     */
    ADD_REP,

    /**
     * The top of the range was hit, but the user's level asks for it twice in a row before the
     * weight moves — see [FitnessLevel]. The weight stays and the target is the top of the
     * range again. *"Hit the top of the range once more to earn the increase."*
     */
    REPEAT,

    /**
     * A working set fell under the bottom of the range last time, so the weight comes down by
     * a tenth and the reps go back to the bottom of the range. *"Last time fell short — take
     * ten percent off and rebuild."*
     */
    DELOAD,
}
