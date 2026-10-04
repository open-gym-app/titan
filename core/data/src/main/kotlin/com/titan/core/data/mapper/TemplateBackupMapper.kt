package com.titan.core.data.mapper

import com.titan.core.data.db.entity.TemplateExerciseEntity
import com.titan.core.data.db.entity.TemplateSetEntity
import com.titan.core.data.db.entity.WorkoutTemplateEntity
import com.titan.core.domain.backup.model.TemplateExerciseRow
import com.titan.core.domain.backup.model.TemplateSetRow
import com.titan.core.domain.backup.model.WorkoutTemplateRow

/** The three template tables to their backup rows and back. See [CatalogueBackupMapper.kt]'s note on the shape. */
fun WorkoutTemplateEntity.toRow(): WorkoutTemplateRow =
    WorkoutTemplateRow(id = id, name = name, category = category, icon = icon)

fun WorkoutTemplateRow.toEntity(): WorkoutTemplateEntity =
    WorkoutTemplateEntity(id = id, name = name, category = category, icon = icon)

fun TemplateExerciseEntity.toRow(): TemplateExerciseRow =
    TemplateExerciseRow(
        id = id,
        templateId = templateId,
        exerciseId = exerciseId,
        position = position,
        supersetGroup = supersetGroup,
        restDuration = restDuration,
        notes = notes,
    )

fun TemplateExerciseRow.toEntity(): TemplateExerciseEntity =
    TemplateExerciseEntity(
        id = id,
        templateId = templateId,
        exerciseId = exerciseId,
        position = position,
        supersetGroup = supersetGroup,
        restDuration = restDuration,
        notes = notes,
    )

fun TemplateSetEntity.toRow(): TemplateSetRow =
    TemplateSetRow(
        id = id,
        templateExerciseId = templateExerciseId,
        setNumber = setNumber,
        setType = setType,
        reps = reps,
        weight = weight,
        duration = duration,
        distance = distance,
    )

fun TemplateSetRow.toEntity(): TemplateSetEntity =
    TemplateSetEntity(
        id = id,
        templateExerciseId = templateExerciseId,
        setNumber = setNumber,
        setType = setType,
        reps = reps,
        weight = weight,
        duration = duration,
        distance = distance,
    )
