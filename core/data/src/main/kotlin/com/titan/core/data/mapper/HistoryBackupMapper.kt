package com.titan.core.data.mapper

import com.titan.core.data.db.entity.SessionExerciseEntity
import com.titan.core.data.db.entity.SessionLootClaimEntity
import com.titan.core.data.db.entity.SessionXpClaimEntity
import com.titan.core.data.db.entity.WorkoutSessionEntity
import com.titan.core.data.db.entity.WorkoutSetEntity
import com.titan.core.domain.backup.model.SessionExerciseRow
import com.titan.core.domain.backup.model.SessionLootClaimRow
import com.titan.core.domain.backup.model.SessionXpClaimRow
import com.titan.core.domain.backup.model.WorkoutSessionRow
import com.titan.core.domain.backup.model.WorkoutSetRow

/**
 * The workout history and its two claim tables, to backup rows and back. See
 * [CatalogueBackupMapper.kt]'s note on why these are top-level functions.
 *
 * `name` passes through as it is, null included: docs/PERSISTENCE.md is explicit that a null
 * `workout_session.name` means genuinely unnamed and is not the same value as `''`, which is
 * what the date-derived title on the history screen keys off. Nothing here normalises it —
 * a round trip has to give back the row that was exported, not the row the write path would
 * have produced.
 */
fun WorkoutSessionEntity.toRow(): WorkoutSessionRow =
    WorkoutSessionRow(
        id = id,
        templateId = templateId,
        name = name,
        completedAt = completedAt,
        isInProgress = isInProgress,
        lastActiveAt = lastActiveAt,
        notes = notes,
        calories = calories,
        rating = rating,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun WorkoutSessionRow.toEntity(): WorkoutSessionEntity =
    WorkoutSessionEntity(
        id = id,
        templateId = templateId,
        name = name,
        completedAt = completedAt,
        isInProgress = isInProgress,
        lastActiveAt = lastActiveAt,
        notes = notes,
        calories = calories,
        rating = rating,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun SessionExerciseEntity.toRow(): SessionExerciseRow =
    SessionExerciseRow(
        id = id,
        sessionId = sessionId,
        exerciseId = exerciseId,
        position = position,
        supersetGroup = supersetGroup,
        templateExerciseId = templateExerciseId,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun SessionExerciseRow.toEntity(): SessionExerciseEntity =
    SessionExerciseEntity(
        id = id,
        sessionId = sessionId,
        exerciseId = exerciseId,
        position = position,
        supersetGroup = supersetGroup,
        templateExerciseId = templateExerciseId,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun WorkoutSetEntity.toRow(): WorkoutSetRow =
    WorkoutSetRow(
        id = id,
        sessionExerciseId = sessionExerciseId,
        setNumber = setNumber,
        reps = reps,
        weight = weight,
        duration = duration,
        distance = distance,
        heartRateAvg = heartRateAvg,
        heartRateMax = heartRateMax,
        elevationGain = elevationGain,
        steps = steps,
        restAfter = restAfter,
        rpe = rpe,
        setType = setType,
        completedAt = completedAt,
        isCompleted = isCompleted,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun WorkoutSetRow.toEntity(): WorkoutSetEntity =
    WorkoutSetEntity(
        id = id,
        sessionExerciseId = sessionExerciseId,
        setNumber = setNumber,
        reps = reps,
        weight = weight,
        duration = duration,
        distance = distance,
        heartRateAvg = heartRateAvg,
        heartRateMax = heartRateMax,
        elevationGain = elevationGain,
        steps = steps,
        restAfter = restAfter,
        rpe = rpe,
        setType = setType,
        completedAt = completedAt,
        isCompleted = isCompleted,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun SessionXpClaimEntity.toRow(): SessionXpClaimRow = SessionXpClaimRow(sessionId = sessionId)

fun SessionXpClaimRow.toEntity(): SessionXpClaimEntity = SessionXpClaimEntity(sessionId = sessionId)

fun SessionLootClaimEntity.toRow(): SessionLootClaimRow = SessionLootClaimRow(sessionId = sessionId)

fun SessionLootClaimRow.toEntity(): SessionLootClaimEntity = SessionLootClaimEntity(sessionId = sessionId)
