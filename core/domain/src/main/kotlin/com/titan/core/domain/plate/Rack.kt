package com.titan.core.domain.plate

import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UserPreferences

/**
 * How a weight moves for one exercise on one user's equipment: the step it moves in, and the
 * rounding that makes every answer a weight the user can actually pick up.
 *
 * ### Why this is not [PlateCalculator]
 *
 * That one answers "which plates make 82.5 kg". This answers "what *is* the weight", which is
 * the question every suggestion asks first and which has three different answers depending on
 * what the movement is loaded with. A barbell is the calculator's case. A dumbbell rack and a
 * machine stack are not loaded in pairs at all, but they still move in steps, and a suggestion
 * of 41.3 kg on either is a number the user has to round themselves. Bodyweight work has no
 * step, and the callers handle that by not having a weight to move.
 *
 * ### Two readers, one rule
 *
 * [com.titan.core.domain.load.NextLoadEngine] adds a step or takes ten percent off; the
 * warm-up planner ([com.titan.core.domain.warmup.WarmupPlanner]) takes half and three
 * quarters of the working weight for its ramp-up sets. Those are three fractions of the same
 * arithmetic, and a second copy of it would be the place where "loadable" stops meaning the
 * same thing on the two screens that draw it.
 *
 * @property step what one increment adds, in milligrams. Always positive.
 * @property bar the empty bar for a plate-loaded exercise, or **null** for anything else — the
 *   flag that decides whether [PlateCalculator] gets the last word.
 */
class Rack(
    val step: Long,
    private val bar: Long?,
    private val plates: PlateInventory,
) {

    /**
     * [weight] plus the step, as the equipment can make it — or **null** when the rack cannot
     * make anything heavier than [weight].
     *
     * The calculator caps a bar at what the rack holds, so a lift logged above that — an
     * inventory with no plates, a session at a better-stocked gym — would otherwise come back
     * as an "increase" to a weight under or equal to the one just lifted. Null is what lets the
     * caller say something true instead.
     */
    fun increase(weight: Long): Long? = loadable(weight + step).takeIf { it > weight + PlateSolution.WEIGHT_TOLERANCE }

    /**
     * [numerator]/[denominator] of [weight], rounded to something the equipment can be: the
     * calculator's own answer for a bar, and the step below for anything else.
     *
     * Never less than one step, because a fraction of a light weight is not a set anybody can
     * load, and never more than [weight], because a lift under one step (or under the bar) must
     * not scale *upwards* to it.
     *
     * The floor is taken a gram above the target, for the reason [PlateSolution.WEIGHT_TOLERANCE]
     * exists: ninety percent of 50 lb is nine steps of 5 lb exactly, and in rounded milligrams
     * it is nine steps less one milligram, which an honest floor turns into eight — a 40 lb
     * dumbbell for a lifter who should be reaching for the 45.
     *
     * @param numerator at least 1, and at most [denominator]: this scales a weight down, and a
     *   caller wanting it to go up wants [increase].
     */
    fun scale(
        weight: Long,
        numerator: Long,
        denominator: Long,
    ): Long {
        require(numerator in 1..denominator) {
            "a scale factor is a fraction of the weight, was $numerator/$denominator"
        }
        val target = weight * numerator / denominator
        val floored =
            if (bar != null) {
                loadable(target)
            } else {
                ((target + PlateSolution.WEIGHT_TOLERANCE) / step * step).coerceAtLeast(step)
            }
        return floored.coerceAtMost(weight)
    }

    /** [target] as the bar can be loaded, or the empty bar for a target under it. */
    private fun loadable(target: Long): Long {
        bar ?: return target
        return when (val solution = PlateCalculator.solve(target = target, barWeight = bar, inventory = plates)) {
            is PlateSolution.BelowBar -> bar
            is PlateSolution.Loaded -> solution.achieved
        }
    }

    companion object {

        /**
         * The rack [exercise] is loaded from, for a user with these [preferences].
         *
         * The step is the smallest change the user's own rack can make — twice its smallest
         * plate, see [PlateInventory.loadStep] — for every kind of equipment, because a
         * dumbbell rack and a machine stack have a step too and a wrong one is corrected in a
         * tap where a percentage rule's 41.3 kg is not. Under [TrainingGoal.CUSTOM] the user's
         * own [UserPreferences.customIncrement] replaces it.
         *
         * On a plate-loaded bar the rack's own step is the floor under that override: a custom
         * increment of 1 kg on a rack whose lightest pair is 1.25 kg rounds back through
         * [PlateCalculator] to the weight just lifted, and a suggestion to add weight that adds
         * none is worse than none.
         */
        fun of(
            preferences: UserPreferences,
            exercise: Exercise,
        ): Rack {
            val plates = preferences.plates
            // The user's rack, or the standard one for their units when theirs holds nothing:
            // a bar with no plates has no step, and a step is needed even for the dumbbells.
            val rackStep = plates.loadStep ?: checkNotNull(
                PlateInventory.defaultFor(preferences.unitSystem).loadStep,
            )
            val custom = preferences.customIncrement?.takeIf { preferences.trainingGoal == TrainingGoal.CUSTOM }
            val plateLoaded = exercise.equipmentType?.isPlateLoaded == true
            return Rack(
                step = if (plateLoaded) (custom ?: rackStep).coerceAtLeast(rackStep) else custom ?: rackStep,
                bar = preferences.barWeight.takeIf { plateLoaded },
                plates = plates,
            )
        }
    }
}
