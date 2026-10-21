package com.titan.core.data.mapper

import com.titan.core.data.db.entity.ExerciseEntity
import com.titan.core.data.db.entity.ExerciseMuscleGroupEntity
import com.titan.core.data.db.entity.ExerciseTagEntity
import com.titan.core.data.db.entity.MuscleGroupEntity
import com.titan.core.data.db.entity.TagEntity
import com.titan.core.domain.backup.model.ExerciseMuscleGroupRow
import com.titan.core.domain.backup.model.ExerciseRow
import com.titan.core.domain.backup.model.ExerciseTagRow
import com.titan.core.domain.backup.model.MuscleGroupRow
import com.titan.core.domain.backup.model.TagRow

/**
 * The exercise library's entities to their backup rows and back, column for column.
 *
 * ### Top-level functions, not an injected mapper class
 *
 * Every other mapper in this package is a `class …Mapper @Inject constructor()`, because it is
 * *interpreting*: `ExerciseMapper` parses enum-like text columns through [EnumColumn], folds a
 * row and its links into one model, and can fail. These do none of that. A backup row is the
 * table restated — same names, same types, same nulls — so the mapping is a rename and nothing
 * else, and there is nothing for a class to hold or a test to stub. Nineteen injectable classes
 * whose bodies are all `Entity(a = row.a, b = row.b)` would be nineteen bindings in a Hilt
 * module for no behaviour.
 *
 * ### Nothing is interpreted on the way through
 *
 * `exercise_type`, `discipline`, `equipment_type`, `mechanic_type` and `difficulty_level` stay
 * `String` here rather than becoming their domain enums, which is deliberate and is the same
 * rule `achievement` and `collected_item` follow: a value this build does not recognise is a
 * row from a newer version, and docs/PERSISTENCE.md says a restore must preserve it. Mapping
 * through an enum would turn every such value into a null on the way out and lose it for good.
 */
fun MuscleGroupEntity.toRow(): MuscleGroupRow = MuscleGroupRow(id = id, name = name, icon = icon)

fun MuscleGroupRow.toEntity(): MuscleGroupEntity = MuscleGroupEntity(id = id, name = name, icon = icon)

fun TagEntity.toRow(): TagRow = TagRow(id = id, name = name, color = color, isSystem = isSystem)

fun TagRow.toEntity(): TagEntity = TagEntity(id = id, name = name, color = color, isSystem = isSystem)

fun ExerciseEntity.toRow(): ExerciseRow =
    ExerciseRow(
        id = id,
        name = name,
        exerciseType = exerciseType,
        discipline = discipline,
        equipmentType = equipmentType,
        machineBrand = machineBrand,
        mechanicType = mechanicType,
        difficultyLevel = difficultyLevel,
        isUnilateral = isUnilateral,
        defaultRest = defaultRest,
        notes = notes,
        instructions = instructions,
        isCustom = isCustom,
        icon = icon,
    )

fun ExerciseRow.toEntity(): ExerciseEntity =
    ExerciseEntity(
        id = id,
        name = name,
        exerciseType = exerciseType,
        discipline = discipline,
        equipmentType = equipmentType,
        machineBrand = machineBrand,
        mechanicType = mechanicType,
        difficultyLevel = difficultyLevel,
        isUnilateral = isUnilateral,
        defaultRest = defaultRest,
        notes = notes,
        instructions = instructions,
        isCustom = isCustom,
        icon = icon,
    )

fun ExerciseMuscleGroupEntity.toRow(): ExerciseMuscleGroupRow =
    ExerciseMuscleGroupRow(exerciseId = exerciseId, muscleGroupId = muscleGroupId, isPrimary = isPrimary)

fun ExerciseMuscleGroupRow.toEntity(): ExerciseMuscleGroupEntity =
    ExerciseMuscleGroupEntity(exerciseId = exerciseId, muscleGroupId = muscleGroupId, isPrimary = isPrimary)

fun ExerciseTagEntity.toRow(): ExerciseTagRow = ExerciseTagRow(exerciseId = exerciseId, tagId = tagId)

fun ExerciseTagRow.toEntity(): ExerciseTagEntity = ExerciseTagEntity(exerciseId = exerciseId, tagId = tagId)
