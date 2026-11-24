package com.titan.core.data.db

import com.titan.core.data.db.entity.BodyMeasurementEntity
import com.titan.core.data.db.entity.ExerciseEntity
import com.titan.core.data.db.entity.ExerciseMuscleGroupEntity
import com.titan.core.data.db.entity.MuscleGroupEntity
import com.titan.core.data.db.entity.PersonalRecordEntity
import com.titan.core.data.db.entity.SessionExerciseEntity
import com.titan.core.data.db.entity.TagEntity
import com.titan.core.data.db.entity.TemplateExerciseEntity
import com.titan.core.data.db.entity.WorkoutSessionEntity
import com.titan.core.data.db.entity.WorkoutSetEntity
import com.titan.core.data.db.entity.WorkoutTemplateEntity

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
    /** The `Discipline` name, for the reads that filter on it — the warm-up catalogue above all. */
    discipline: String? = null,
) = ExerciseEntity(
    name = name,
    exerciseType = exerciseType,
    equipmentType = equipmentType,
    machineBrand = machineBrand,
    discipline = discipline,
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

/**
 * @param completedAt when the session was closed out. A finished session always carries one
 *   — `FinishSessionUseCase` writes it and `is_in_progress = 0` together — so the analytics
 *   that bucket by it would read nothing at all from a fixture that left it null.
 */
fun session(
    templateId: Long? = null,
    name: String? = null,
    isInProgress: Boolean = true,
    lastActiveAt: Long = 0,
    createdAt: Long = 0,
    completedAt: Long? = null,
) = WorkoutSessionEntity(
    templateId = templateId,
    name = name,
    isInProgress = isInProgress,
    lastActiveAt = lastActiveAt,
    createdAt = createdAt,
    updatedAt = createdAt,
    completedAt = completedAt,
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

fun bodyMeasurement(
    weight: Long? = 80_000_000,
    neck: Int? = null,
    shoulders: Int? = null,
    chest: Int? = null,
    waist: Int? = null,
    hips: Int? = null,
    arm: Int? = null,
    forearm: Int? = null,
    thigh: Int? = null,
    calf: Int? = null,
    bodyFat: Int? = null,
    notes: String? = null,
    createdAt: Long = 0,
) = BodyMeasurementEntity(
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
    updatedAt = createdAt,
)
