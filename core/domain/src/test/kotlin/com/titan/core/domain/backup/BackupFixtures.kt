package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.AchievementRow
import com.titan.core.domain.backup.model.BodyMeasurementRow
import com.titan.core.domain.backup.model.CollectedItemRow
import com.titan.core.domain.backup.model.ExerciseMuscleGroupRow
import com.titan.core.domain.backup.model.ExerciseRow
import com.titan.core.domain.backup.model.ExerciseTagRow
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.GymRow
import com.titan.core.domain.backup.model.MuscleGroupRow
import com.titan.core.domain.backup.model.PersonalRecordRow
import com.titan.core.domain.backup.model.PlateStockPayload
import com.titan.core.domain.backup.model.PreferencesPayload
import com.titan.core.domain.backup.model.QuestCompletionRow
import com.titan.core.domain.backup.model.SessionExerciseRow
import com.titan.core.domain.backup.model.SessionLootClaimRow
import com.titan.core.domain.backup.model.SessionXpClaimRow
import com.titan.core.domain.backup.model.TagRow
import com.titan.core.domain.backup.model.TemplateExerciseRow
import com.titan.core.domain.backup.model.TemplateSetRow
import com.titan.core.domain.backup.model.UserProgressRow
import com.titan.core.domain.backup.model.WorkoutSessionRow
import com.titan.core.domain.backup.model.WorkoutSetRow
import com.titan.core.domain.backup.model.WorkoutTemplateRow

/**
 * [this] as an [ImportSource], for a test that just built the document it wants decoded.
 *
 * The one place a string becomes a source, so the awkwardness stays in the tests where it
 * belongs: a test holds the whole document because it wrote it a line ago, and nothing on a
 * phone ever will — see [ImportSource] for what that costs at 50,000 sets.
 */
fun String.asSource(): ImportSource = ImportSource { byteInputStream() }

/**
 * A document with **every table non-empty** and every reference resolving, for the tests that
 * need a valid whole rather than one interesting row.
 *
 * Every table populated is the point rather than a convenience: a fixture that left three of
 * twenty empty would let the streaming writer, the validator and the round trip all pass
 * while silently dropping them. Tests that want a violation start here and break one thing, so
 * what they assert on is that one difference.
 */
fun sampleDocument(
    exportedAt: Long = EXPORTED_AT,
    appVersion: String = APP_VERSION,
    preferences: PreferencesPayload = samplePreferences(),
): ExportDocument =
    ExportDocument(
        exportedAt = exportedAt,
        appVersion = appVersion,
        preferences = preferences,
        muscleGroup = sampleMuscleGroups(),
        tag = sampleTags(),
        exercise = sampleExercises(),
        exerciseMuscleGroup = listOf(
            ExerciseMuscleGroupRow(exerciseId = 1, muscleGroupId = 1, isPrimary = true),
            ExerciseMuscleGroupRow(exerciseId = 1, muscleGroupId = 2, isPrimary = false),
        ),
        exerciseTag = listOf(ExerciseTagRow(exerciseId = 1, tagId = 1)),
        workoutTemplate = listOf(WorkoutTemplateRow(id = 1, name = "Push Day", category = "STRENGTH")),
        templateExercise = listOf(
            TemplateExerciseRow(id = 1, templateId = 1, exerciseId = 1, position = 0, notes = "Pause on the chest."),
        ),
        templateSet = listOf(
            TemplateSetRow(id = 1, templateExerciseId = 1, setNumber = 1, reps = 5, weight = 60_000_000),
        ),
        workoutSession = sampleSessions(),
        sessionExercise = sampleSessionExercises(),
        workoutSet = sampleSets(),
        personalRecord = listOf(
            PersonalRecordRow(
                id = 1,
                exerciseId = 1,
                workoutSetId = 1,
                prType = "MAX_WEIGHT",
                weight = 100_000_000,
                reps = 5,
                estimated1Rm = 116_666_666,
                achievedAt = FINISHED_AT,
            ),
        ),
        bodyMeasurement = sampleBodyMeasurements(),
        sessionXpClaim = listOf(SessionXpClaimRow(sessionId = 1)),
        sessionLootClaim = listOf(SessionLootClaimRow(sessionId = 1)),
        userProgress = listOf(sampleProgress()),
        achievement = listOf(
            AchievementRow(achievementId = "first_workout", unlockedAt = FINISHED_AT),
            // A milestone this build has never heard of. PERSISTENCE.md says a restore must
            // preserve it, so no test may treat it as corrupt.
            AchievementRow(achievementId = "from_a_newer_version", unlockedAt = FINISHED_AT),
        ),
        questCompletion = listOf(
            QuestCompletionRow(questId = "weekly_volume", periodStart = STARTED_AT, completedAt = FINISHED_AT),
        ),
        collectedItem = sampleCollectedItems(),
        gym = sampleGyms(),
    )

private fun sampleCollectedItems() =
    listOf(
        CollectedItemRow(
            id = 1,
            collectibleId = "steel_shaker",
            source = "session_finished",
            acquiredAt = FINISHED_AT,
        ),
        // The same collectible again: the inventory is a bag, not a set, and an import must
        // not collapse these into one row.
        CollectedItemRow(
            id = 2,
            collectibleId = "steel_shaker",
            source = "session_finished",
            acquiredAt = FINISHED_AT,
        ),
    )

private fun sampleGyms() =
    listOf(
        GymRow(
            id = 1,
            name = "Iron Works",
            address = "12 Forge Street",
            latitudeMicro = 51_507_400,
            longitudeMicro = -127_800,
            equipmentTags = "squat rack\nplates",
            userNotes = "closes at 22:00",
            createdAt = STARTED_AT,
        ),
        GymRow(
            id = 2,
            name = "Park Pull-ups",
            address = "",
            latitudeMicro = -33_868_820,
            longitudeMicro = 151_209_300,
            equipmentTags = "",
            createdAt = STARTED_AT,
        ),
    )

private fun sampleMuscleGroups() =
    listOf(
        MuscleGroupRow(id = 1, name = "CHEST", icon = "chest.webp"),
        MuscleGroupRow(id = 2, name = "TRICEPS"),
    )

private fun sampleTags() =
    listOf(
        TagRow(id = 1, name = "PUSH", color = "#ff0000", isSystem = true),
        TagRow(id = 2, name = "favourites"),
    )

/** One seeded movement with every column filled, and one the user typed in with almost none. */
private fun sampleExercises() =
    listOf(
        ExerciseRow(
            id = 1,
            name = "Bench Press",
            exerciseType = "STRENGTH",
            discipline = "POWERLIFTING",
            equipmentType = "BARBELL",
            machineBrand = null,
            mechanicType = "COMPOUND",
            difficultyLevel = "INTERMEDIATE",
            isUnilateral = false,
            defaultRest = 180,
            notes = "elbows in",
            instructions = "Lie on the bench…",
            isCustom = false,
            icon = "bench.webp",
        ),
        ExerciseRow(id = 2, name = "Sled Push", equipmentType = "OTHER", isCustom = true),
    )

/**
 * One finished session, and one that is unnamed, still running and from no template — the three
 * nullable columns whose null is not the same as a blank. A round trip has to give back nulls.
 */
private fun sampleSessions() =
    listOf(
        WorkoutSessionRow(
            id = 1,
            templateId = 1,
            name = "Push Day",
            completedAt = FINISHED_AT,
            isInProgress = false,
            lastActiveAt = FINISHED_AT,
            notes = "felt strong",
            createdAt = STARTED_AT,
            updatedAt = FINISHED_AT,
        ),
        WorkoutSessionRow(id = 2, lastActiveAt = STARTED_AT, createdAt = STARTED_AT, updatedAt = STARTED_AT),
    )

/** Two exercises of one session, linked as a superset — the grouping has to survive too. */
private fun sampleSessionExercises() =
    listOf(
        SessionExerciseRow(
            id = 1,
            sessionId = 1,
            exerciseId = 1,
            position = 0,
            supersetGroup = 0,
            templateExerciseId = 1,
            notes = "paused reps",
            createdAt = STARTED_AT,
            updatedAt = FINISHED_AT,
        ),
        SessionExerciseRow(
            id = 2,
            sessionId = 1,
            exerciseId = 2,
            position = 1,
            supersetGroup = 1,
            createdAt = STARTED_AT,
            updatedAt = FINISHED_AT,
        ),
    )

/**
 * A performed strength set, and a planned-but-unperformed cardio one — `completed_at = 0` with
 * `is_completed = 0`, the placeholder PERSISTENCE.md documents and which a restore must keep.
 */
private fun sampleSets() =
    listOf(
        WorkoutSetRow(
            id = 1,
            sessionExerciseId = 1,
            setNumber = 1,
            reps = 5,
            weight = 100_000_000,
            rpe = 85,
            restAfter = 180,
            setType = "NORMAL",
            completedAt = FINISHED_AT,
            isCompleted = true,
            createdAt = STARTED_AT,
            updatedAt = FINISHED_AT,
        ),
        WorkoutSetRow(
            id = 2,
            sessionExerciseId = 2,
            setNumber = 1,
            duration = 60,
            distance = 2_000,
            heartRateAvg = 150,
            heartRateMax = 172,
            completedAt = 0,
            isCompleted = false,
            createdAt = STARTED_AT,
            updatedAt = STARTED_AT,
        ),
    )

/** A full trip to the scale and the tape, and an entry carrying a waist and no weight. */
private fun sampleBodyMeasurements() =
    listOf(
        BodyMeasurementRow(
            id = 1,
            weight = 82_500_000,
            chest = 1_050,
            waist = 830,
            arm = 380,
            thigh = 600,
            bodyFat = 152,
            notes = "morning",
            createdAt = STARTED_AT,
            updatedAt = STARTED_AT,
        ),
        BodyMeasurementRow(id = 2, weight = null, waist = 828, createdAt = STARTED_AT, updatedAt = STARTED_AT),
    )

private fun sampleProgress() =
    UserProgressRow(
        id = UserProgressRow.SINGLE_ROW_ID,
        totalXp = 4_200,
        currentLevel = 8,
        currentStreak = 3,
        longestStreak = 11,
        lastCountedAt = FINISHED_AT,
        weekSessions = 2,
        weekGoalMet = true,
        protectionRemaining = 0,
        protectionPeriod = "2026-09",
        protectionConsumedAt = STARTED_AT,
    )

/** Settings with every field set to something other than its default, so a lost one shows up. */
fun samplePreferences(): PreferencesPayload =
    PreferencesPayload(
        unitSystem = "IMPERIAL",
        theme = "DARK",
        defaultRestDurationSeconds = 120,
        barWeight = 45_000_000,
        trainingGoal = "HYPERTROPHY",
        oneRepMaxFormula = "BRZYCKI",
        onboardingCompleted = true,
        plateInventory = listOf(
            PlateStockPayload(weight = 20_000_000, pairs = 4),
            PlateStockPayload(weight = 10_000_000, pairs = 2),
        ),
        fitnessLevel = "ADVANCED",
        customRepRangeMin = 6,
        customRepRangeMax = 10,
        customIncrement = 1_250_000,
        warmupSuggestions = false,
        weeklyWorkoutGoal = 3,
        autoOpenRestTimer = false,
    )

const val EXPORTED_AT: Long = 1_788_686_970_491L
const val STARTED_AT: Long = 1_788_600_000_000L
const val FINISHED_AT: Long = 1_788_603_600_000L
const val APP_VERSION: String = "1.0-debug"
