package com.titan.core.domain.backup.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A whole device's data as one versioned JSON document: every table in the schema, plus the
 * user's settings, plus enough about the writer to diagnose a file that will not restore.
 *
 * ### What it carries, and the three decisions behind that
 *
 * - **`personal_record` is exported, not re-derived.** Re-running the PR engine over imported
 *   history would produce a *plausible* record set rather than *the user's*: the detection
 *   rules can change between the build that wrote the file and the one that reads it, and a
 *   restore that silently rewrites somebody's PR history is a worse failure than a larger file.
 * - **The two claim tables are exported.** `session_xp_claim` and `session_loot_claim` are what
 *   make the post-session cascade idempotent. Dropping them would let a restored session be
 *   paid XP again and rolled for loot again the next time anything touches it.
 * - **[preferences] is exported** and restored *last*. A restore is meant to reproduce a
 *   device, and the unit system in particular changes how every stored weight is read back;
 *   applying it after the rows means the app re-themes once, at the end, rather than mid-write.
 *   This reverses what docs/PERSISTENCE.md said while nothing consumed the store — that note
 *   has been updated to point here.
 *
 * **Not carried:** the in-progress session draft. An interrupted workout is device state rather
 * than history — importing one would drop the user into a live session that never happened on
 * this phone — and the rows it overlaps are exported anyway, so nothing logged is lost. The
 * importer clears whatever draft the target device holds, because a draft naming a session id
 * from before the wipe is a pointer into data that no longer exists.
 *
 * ### The envelope
 *
 * [schemaVersion] is read before anything else is interpreted, which is the whole point of
 * having it: a document written by a build that laid the tables out differently is *refused*,
 * not misread. `ExportFormat` does that literally — it reads the version with the rest of the
 * document left as raw JSON — for the reason `ActiveSessionFormat` does the same with a draft.
 * A Room migration ladder upgrades a database *file*; it has never had anything to say about a
 * JSON document, so a reader accepts its own version and the older ones `ExportUpgrade` has an
 * up-converter for, and nothing else. See docs/export-schema.md for the version history and
 * the compatibility rule.
 *
 * [exportedAt] is epoch milliseconds UTC, like every other timestamp in the schema.
 * [appVersion] is diagnostic only: nothing reads it, nothing branches on it, and a document
 * that disagrees with the running build about it still restores. It is there so a bug report
 * with a file attached says which build wrote it.
 */
@Serializable
data class ExportDocument(
    @SerialName("schema_version") val schemaVersion: Int = SCHEMA_VERSION,
    @SerialName("exported_at") val exportedAt: Long,
    @SerialName("app_version") val appVersion: String,
    @SerialName("preferences") val preferences: PreferencesPayload,
    @SerialName("muscle_group") val muscleGroup: List<MuscleGroupRow> = emptyList(),
    @SerialName("tag") val tag: List<TagRow> = emptyList(),
    @SerialName("exercise") val exercise: List<ExerciseRow> = emptyList(),
    @SerialName("exercise_muscle_group") val exerciseMuscleGroup: List<ExerciseMuscleGroupRow> = emptyList(),
    @SerialName("exercise_tag") val exerciseTag: List<ExerciseTagRow> = emptyList(),
    @SerialName("workout_template") val workoutTemplate: List<WorkoutTemplateRow> = emptyList(),
    @SerialName("template_exercise") val templateExercise: List<TemplateExerciseRow> = emptyList(),
    @SerialName("template_set") val templateSet: List<TemplateSetRow> = emptyList(),
    @SerialName("workout_session") val workoutSession: List<WorkoutSessionRow> = emptyList(),
    @SerialName("session_exercise") val sessionExercise: List<SessionExerciseRow> = emptyList(),
    @SerialName("workout_set") val workoutSet: List<WorkoutSetRow> = emptyList(),
    @SerialName("personal_record") val personalRecord: List<PersonalRecordRow> = emptyList(),
    @SerialName("body_measurement") val bodyMeasurement: List<BodyMeasurementRow> = emptyList(),
    @SerialName("session_xp_claim") val sessionXpClaim: List<SessionXpClaimRow> = emptyList(),
    @SerialName("session_loot_claim") val sessionLootClaim: List<SessionLootClaimRow> = emptyList(),
    @SerialName("user_progress") val userProgress: List<UserProgressRow> = emptyList(),
    @SerialName("achievement") val achievement: List<AchievementRow> = emptyList(),
    @SerialName("quest_completion") val questCompletion: List<QuestCompletionRow> = emptyList(),
    @SerialName("collected_item") val collectedItem: List<CollectedItemRow> = emptyList(),
    @SerialName("gym") val gym: List<GymRow> = emptyList(),
) {

    /**
     * The rows this document holds for [table].
     *
     * The one place a [BackupTable] is turned back into a typed list, so the validator, the
     * importer and the streaming writer all agree about which field belongs to which table
     * rather than each restating the mapping. Exhaustive over the enum: a table added there and
     * not here does not compile.
     */
    fun rows(table: BackupTable): List<BackupRow> =
        when (table) {
            BackupTable.MUSCLE_GROUP -> muscleGroup
            BackupTable.TAG -> tag
            BackupTable.EXERCISE -> exercise
            BackupTable.EXERCISE_MUSCLE_GROUP -> exerciseMuscleGroup
            BackupTable.EXERCISE_TAG -> exerciseTag
            BackupTable.WORKOUT_TEMPLATE -> workoutTemplate
            BackupTable.TEMPLATE_EXERCISE -> templateExercise
            BackupTable.TEMPLATE_SET -> templateSet
            BackupTable.WORKOUT_SESSION -> workoutSession
            BackupTable.SESSION_EXERCISE -> sessionExercise
            BackupTable.WORKOUT_SET -> workoutSet
            BackupTable.PERSONAL_RECORD -> personalRecord
            BackupTable.BODY_MEASUREMENT -> bodyMeasurement
            BackupTable.SESSION_XP_CLAIM -> sessionXpClaim
            BackupTable.SESSION_LOOT_CLAIM -> sessionLootClaim
            BackupTable.USER_PROGRESS -> userProgress
            BackupTable.ACHIEVEMENT -> achievement
            BackupTable.QUEST_COMPLETION -> questCompletion
            BackupTable.COLLECTED_ITEM -> collectedItem
            BackupTable.GYM -> gym
        }

    /** How many rows the whole document holds, across every table. What a progress bar divides by. */
    val rowCount: Long get() = BackupTable.entries.sumOf { rows(it).size.toLong() }

    companion object {

        /**
         * The version stamped into every document written and demanded of every document read.
         *
         * Bump it when a change would make an existing file restore into something other than
         * what was exported: a table added or dropped, a column renamed or retyped, a unit
         * changed. Adding a *nullable* column does not need a bump — it reads back as null on
         * an older file, which is what the row would have held. Every bump owes a row in
         * docs/export-schema.md's version-history table and, if older files are to keep
         * restoring, an up-converter listed there; until one is written, a reader refuses
         * every version but its own, older ones included.
         *
         * 2 added the `gym` table. Version 1 files still restore — see `ExportUpgrade`.
         */
        const val SCHEMA_VERSION: Int = 2
    }
}
