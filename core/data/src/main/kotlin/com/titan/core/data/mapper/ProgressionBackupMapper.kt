package com.titan.core.data.mapper

import com.titan.core.data.db.entity.BodyMeasurementEntity
import com.titan.core.data.db.entity.PersonalRecordEntity
import com.titan.core.domain.backup.model.BodyMeasurementRow
import com.titan.core.domain.backup.model.PersonalRecordRow

/**
 * The two progression tables to backup rows and back. See [CatalogueBackupMapper.kt].
 *
 * Neither derived field travels: `BodyMeasurement.leanMass` and a template's estimated duration
 * are pure functions of columns on the same row, so an export carrying one could only agree
 * with the row or be stale. docs/PERSISTENCE.md says so of both, and the mapping is what makes
 * it true — there is nowhere here for a derived value to be written.
 */
fun PersonalRecordEntity.toRow(): PersonalRecordRow =
    PersonalRecordRow(
        id = id,
        exerciseId = exerciseId,
        workoutSetId = workoutSetId,
        prType = prType,
        weight = weight,
        reps = reps,
        distance = distance,
        duration = duration,
        estimated1Rm = estimated1Rm,
        achievedAt = achievedAt,
    )

fun PersonalRecordRow.toEntity(): PersonalRecordEntity =
    PersonalRecordEntity(
        id = id,
        exerciseId = exerciseId,
        workoutSetId = workoutSetId,
        prType = prType,
        weight = weight,
        reps = reps,
        distance = distance,
        duration = duration,
        estimated1Rm = estimated1Rm,
        achievedAt = achievedAt,
    )

fun BodyMeasurementEntity.toRow(): BodyMeasurementRow =
    BodyMeasurementRow(
        id = id,
        weight = weight,
        neck = neck,
        shoulders = shoulders,
        chest = chest,
        waist = waist,
        hips = hips,
        arm = arm,
        forearm = forearm,
        thigh = thigh,
        calf = calf,
        bodyFat = bodyFat,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun BodyMeasurementRow.toEntity(): BodyMeasurementEntity =
    BodyMeasurementEntity(
        id = id,
        weight = weight,
        neck = neck,
        shoulders = shoulders,
        chest = chest,
        waist = waist,
        hips = hips,
        arm = arm,
        forearm = forearm,
        thigh = thigh,
        calf = calf,
        bodyFat = bodyFat,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
