package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.PersonalRecordEntity
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import javax.inject.Inject

/**
 * [PersonalRecordEntity] to [PersonalRecord].
 *
 * One direction only. Records are written by the PR engine (#30) and by nothing else, so
 * there is no caller for the reverse and an unused `toEntity` would be a shape nobody has
 * checked against the engine's actual needs.
 *
 * A row whose `pr_type` this build cannot read is **dropped**, which is why [toDomainOrNull]
 * is nullable where the other mappers are not. Every other enum column has a defensible
 * fallback — an unknown equipment is `OTHER`, an unknown exercise type is `STRENGTH` — but
 * the type here says which of the five figures carries the value, so guessing it would put a
 * weight under "most reps" on a screen. Not showing a record the app cannot read is the
 * honest outcome; showing the wrong one is not.
 */
class PersonalRecordMapper @Inject constructor() {

    fun toDomainOrNull(entity: PersonalRecordEntity): PersonalRecord? {
        val type = decodeEnum<PrType>(entity.prType) ?: return null
        return PersonalRecord(
            id = entity.id,
            exerciseId = entity.exerciseId,
            workoutSetId = entity.workoutSetId,
            type = type,
            weight = entity.weight,
            reps = entity.reps,
            distance = entity.distance,
            duration = entity.duration,
            estimated1Rm = entity.estimated1Rm,
            achievedAt = entity.achievedAt,
        )
    }
}
