package com.titan.core.data.mapper

import com.titan.core.data.db.entity.PetStateEntity
import com.titan.core.domain.backup.model.PetStateRow
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanState
import com.titan.core.domain.model.TitanVitals

/**
 * This row as a [TitanState], degraded rather than trusted: vitals clamped, an unknown activity
 * read as idle and an unknown one to resume read as none. Timestamps are repaired against the
 * clock by the repository, which is the one that knows what "now" is.
 */
fun PetStateEntity.toDomain(): TitanState =
    TitanState(
        vitals = TitanVitals.clamped(fuel = fuel, energy = energy, spirit = spirit),
        activity = TitanActivity.fromKey(activity),
        startedAt = activityStartedAt,
        endsAt = activityEndsAt,
        resumeActivity = resumeActivity?.let { key -> TitanActivity.entries.firstOrNull { it.key == key } },
        updatedAt = updatedAt,
    )

fun TitanState.toEntity(petId: Long): PetStateEntity =
    PetStateEntity(
        petId = petId,
        fuel = vitals.fuel,
        energy = vitals.energy,
        spirit = vitals.spirit,
        activity = activity.key,
        activityStartedAt = startedAt,
        activityEndsAt = endsAt,
        resumeActivity = resumeActivity?.key,
        updatedAt = updatedAt,
    )

fun PetStateEntity.toRow(): PetStateRow =
    PetStateRow(
        petId = petId,
        fuel = fuel,
        energy = energy,
        spirit = spirit,
        activity = activity,
        activityStartedAt = activityStartedAt,
        activityEndsAt = activityEndsAt,
        resumeActivity = resumeActivity,
        updatedAt = updatedAt,
    )

/** The restore boundary's half of the repair: vitals into range. Keys are kept as written. */
fun PetStateRow.toEntity(): PetStateEntity =
    PetStateEntity(
        petId = petId,
        fuel = fuel.coerceIn(0, TitanVitals.MAX),
        energy = energy.coerceIn(0, TitanVitals.MAX),
        spirit = spirit.coerceIn(0, TitanVitals.MAX),
        activity = activity,
        activityStartedAt = activityStartedAt,
        activityEndsAt = activityEndsAt,
        resumeActivity = resumeActivity,
        updatedAt = updatedAt,
    )
