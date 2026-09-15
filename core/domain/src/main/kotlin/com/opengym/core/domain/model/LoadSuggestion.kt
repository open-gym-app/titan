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
 * @property weight the load to put on, in milligrams, or null for a bodyweight movement.
 * @property reps how many reps to aim for. Always at least one.
 * @property reason which rule produced this, for the one line the screen says under it.
 */
data class LoadSuggestion(
    val weight: Long?,
    val reps: Int,
    val reason: SuggestionReason,
) {
    init {
        require(weight == null || weight > 0) { "a suggested weight must be positive or absent, was $weight mg" }
        require(reps >= 1) { "a suggestion must prescribe at least one rep, was $reps" }
    }
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
     * range — add weight."*
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
