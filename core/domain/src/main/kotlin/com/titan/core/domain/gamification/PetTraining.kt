package com.titan.core.domain.gamification

import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PetStat

/**
 * Which stat each kind of exercise grows — stated once.
 */
object PetTraining {

    /**
     * The stat a set of [type] grows.
     *
     * A `when` with no `else`, on purpose: an [ExerciseType] added later fails compilation here
     * rather than silently growing nothing.
     */
    fun statFor(type: ExerciseType): PetStat =
        when (type) {
            ExerciseType.STRENGTH -> PetStat.STRENGTH
            ExerciseType.ENDURANCE -> PetStat.ENDURANCE
            ExerciseType.ISOMETRIC -> PetStat.MOBILITY
        }
}
