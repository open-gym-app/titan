package com.opengym.core.data.db

import com.opengym.core.data.db.entity.BodyWeightLogEntity
import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.data.db.entity.ExerciseMuscleGroupEntity
import com.opengym.core.data.db.entity.MuscleGroupEntity
import com.opengym.core.data.db.entity.PersonalRecordEntity
import com.opengym.core.data.db.entity.SessionExerciseEntity
import com.opengym.core.data.db.entity.TagEntity
import com.opengym.core.data.db.entity.TemplateExerciseEntity
import com.opengym.core.data.db.entity.WorkoutSessionEntity
import com.opengym.core.data.db.entity.WorkoutSetEntity
import com.opengym.core.data.db.entity.WorkoutTemplateEntity

/**
 * Entity factories with valid defaults, so a test only spells out the columns it
 * actually asserts on. They are top-level in the `db` test package rather than
 * members of [RoomTestBase] so that shared helpers - [WorkoutHistoryFixture] in
 * particular - build their rows the same way instead of duplicating the defaults.
 */

fun exercise(
    name: String = "Bench Press",
    exerciseType: String = "STRENGTH",
    equipmentType: String? = "BARBELL",
    machineBrand: String? = null,
) = ExerciseEntity(
    name = name,
    exerciseType = exerciseType,
    equipmentType = equipmentType,
    machineBrand = machineBrand,
)

fun muscleGroup(
    name: String = "CHEST",
    icon: String? = null,
) = MuscleGroupEntity(name = name, icon = icon)

fun tag(
    name: String = "PUSH",
    isSystem: Boolean = false,
) = TagEntity(name = name, isSystem = isSystem)

fun template(
    name: String = "Push Day",
    category: String? = null,
) = WorkoutTemplateEntity(name = name, category = category)

fun templateExercise(
    templateId: Long,
    exerciseId: Long,
    position: Int,
    supersetGroup: Int? = null,
) = TemplateExerciseEntity(
    templateId = templateId,
    exerciseId = exerciseId,
    position = position,
    supersetGroup = supersetGroup,
)

fun session(
    templateId: Long? = null,
    name: String? = null,
    isInProgress: Boolean = true,
    lastActiveAt: Long = 0,
    createdAt: Long = 0,
) = WorkoutSessionEntity(
    templateId = templateId,
    name = name,
    isInProgress = isInProgress,
    lastActiveAt = lastActiveAt,
    createdAt = createdAt,
    updatedAt = createdAt,
)

fun sessionExercise(
    sessionId: Long,
    exerciseId: Long,
    position: Int,
    templateExerciseId: Long? = null,
    supersetGroup: Int? = null,
) = SessionExerciseEntity(
    sessionId = sessionId,
    exerciseId = exerciseId,
    position = position,
    supersetGroup = supersetGroup,
    templateExerciseId = templateExerciseId,
    createdAt = 0,
    updatedAt = 0,
)

fun workoutSet(
    sessionExerciseId: Long,
    setNumber: Int,
    isCompleted: Boolean = true,
    completedAt: Long = 0,
    reps: Int? = null,
    weight: Long? = null,
    setType: String = "NORMAL",
) = WorkoutSetEntity(
    sessionExerciseId = sessionExerciseId,
    setNumber = setNumber,
    reps = reps,
    weight = weight,
    setType = setType,
    isCompleted = isCompleted,
    completedAt = completedAt,
    createdAt = 0,
    updatedAt = 0,
)

fun exerciseMuscleGroup(
    exerciseId: Long,
    muscleGroupId: Long,
    isPrimary: Boolean = true,
) = ExerciseMuscleGroupEntity(
    exerciseId = exerciseId,
    muscleGroupId = muscleGroupId,
    isPrimary = isPrimary,
)

fun personalRecord(
    exerciseId: Long,
    workoutSetId: Long,
    prType: String = "MAX_WEIGHT",
    achievedAt: Long = 0,
) = PersonalRecordEntity(
    exerciseId = exerciseId,
    workoutSetId = workoutSetId,
    prType = prType,
    achievedAt = achievedAt,
)

fun bodyWeightLog(
    weight: Long = 80_000_000,
    createdAt: Long = 0,
) = BodyWeightLogEntity(weight = weight, createdAt = createdAt, updatedAt = createdAt)
