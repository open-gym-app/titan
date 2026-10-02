package com.titan.core.data.mapper

import com.titan.core.data.db.entity.WorkoutSetEntity
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.WorkoutSet
import javax.inject.Inject

/**
 * [WorkoutSetEntity] to [WorkoutSet] and back.
 *
 * The only mapper with anything to decide: `workout_set` carries two constraints
 * SQLite cannot express — the `set_type` vocabulary and the RPE range — so the
 * entity can legally hold values the domain model's `init` block refuses. Writes
 * come through [WorkoutSet] and so are always sound; a row that is not can only
 * come from an import or an older build, and neither is a reason to make the
 * session it belongs to unreadable.
 */
class WorkoutSetMapper @Inject constructor() {

    fun toDomain(entity: WorkoutSetEntity): WorkoutSet =
        WorkoutSet(
            id = entity.id,
            sessionExerciseId = entity.sessionExerciseId,
            setNumber = entity.setNumber,
            reps = entity.reps,
            weight = entity.weight,
            duration = entity.duration,
            distance = entity.distance,
            heartRateAvg = entity.heartRateAvg,
            heartRateMax = entity.heartRateMax,
            restAfter = entity.restAfter,
            // Out of range reads as "no RPE recorded", the value the column already
            // uses for a set the user never rated. Passing it through would throw from
            // WorkoutSet's init and take the whole query's result with it, to save an
            // optional annotation on one set.
            rpe = entity.rpe?.takeIf { it in WorkoutSet.RPE_MIN..WorkoutSet.RPE_MAX },
            setType = decodeEnum<SetType>(entity.setType) ?: SetType.NORMAL,
            completedAt = entity.completedAt,
            isCompleted = entity.isCompleted,
            notes = entity.notes,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )

    fun toEntity(set: WorkoutSet): WorkoutSetEntity =
        WorkoutSetEntity(
            id = set.id,
            sessionExerciseId = set.sessionExerciseId,
            setNumber = set.setNumber,
            reps = set.reps,
            weight = set.weight,
            duration = set.duration,
            distance = set.distance,
            heartRateAvg = set.heartRateAvg,
            heartRateMax = set.heartRateMax,
            restAfter = set.restAfter,
            rpe = set.rpe,
            setType = set.setType.name,
            completedAt = set.completedAt,
            isCompleted = set.isCompleted,
            notes = set.notes,
            createdAt = set.createdAt,
            updatedAt = set.updatedAt,
        )
}
