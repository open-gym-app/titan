package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.PersonalRecordEntity
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import javax.inject.Inject

/**
 * [PersonalRecordEntity] to and from [PersonalRecord].
 *
 * [toEntity] exists for exactly one caller, [PersonalRecordRepositoryImpl.insert] — the PR
 * engine (#30) is the only writer this table has, per [PersonalRecordRepository]'s contract.
 *
 * A row whose `pr_type` this build cannot read is **dropped** on the way in, which is why
 * [toDomainOrNull] is nullable where the other mappers are not. Every other enum column has
 * a defensible fallback — an unknown equipment is `OTHER`, an unknown exercise type is
 * `STRENGTH` — but the type here says which of the five figures carries the value, so
 * guessing it would put a weight under "most reps" on a screen. Not showing a record the app
 * cannot read is the honest outcome; showing the wrong one is not. [toEntity] has no such
 * case to handle: [PersonalRecord.type] is the domain's own enum, not a string that arrived
 * from somewhere less trustworthy.
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

    fun toEntity(record: PersonalRecord): PersonalRecordEntity =
        PersonalRecordEntity(
            id = record.id,
            exerciseId = record.exerciseId,
            workoutSetId = record.workoutSetId,
            prType = record.type.name,
            weight = record.weight,
            reps = record.reps,
            distance = record.distance,
            duration = record.duration,
            estimated1Rm = record.estimated1Rm,
            achievedAt = record.achievedAt,
        )
}
