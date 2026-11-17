package com.titan.core.domain.backup.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of one exported table, as it is written to JSON.
 *
 * ### These mirror the columns, not the domain
 *
 * Every property below carries an explicit [SerialName] equal to its SQLite column, and every
 * subtype's [SerialName] set is exactly its table's column set — `ExportSchemaTest` reads
 * `core/data/schemas/…/1.json` and asserts it, column for column, so a column added to an
 * entity and not to a row here fails a test rather than dropping silently out of every backup
 * taken afterwards.
 *
 * That is a deliberate choice against reusing the domain models. `WorkoutSet`,
 * `PersonalRecord` and the rest are shaped for the app to *reason* with — they fold columns
 * together, derive fields on read (`BodyMeasurement.leanMass`, a template's duration) and drop
 * the ones no screen needs. An export has the opposite job: it is the only copy of the user's
 * data, so it has to carry what is *stored*, and it has to keep carrying it when a domain
 * model is refactored. Pinning the wire shape to the table means a rename in `:core:domain`
 * cannot change the format, and a schema change cannot fail to.
 *
 * ### Ids are exported verbatim, and that is only safe because import replaces
 *
 * docs/PERSISTENCE.md says ids are not portable across devices and that anything matching rows
 * across installs must match on the natural key and remap. That rule is about a *merge*. This
 * format does not merge: an import wipes every table and re-inserts the document whole, so the
 * ids in the file are the only ids in the database afterwards and every foreign key in it
 * still resolves to the row it was written against. Remapping would buy nothing and would need
 * a conflict rule per table — see docs/export-schema.md for why replace is the semantics.
 *
 * ### Defaults mirror the schema's defaults
 *
 * A `NOT NULL` column with no SQL default is a required key here: a document missing it is
 * malformed, not a document with a zero in it. A nullable column defaults to null, and a
 * `NOT NULL` column with a SQL default carries that same default — so a hand-edited file that
 * omits `is_completed` restores the row SQLite itself would have written. Encoding always
 * writes every field regardless (see `ExportFormat`); the defaults are read-side tolerance.
 *
 * ### The interface itself is deliberately not `@Serializable`
 *
 * Marking it would generate a sealed polymorphic serializer, and every row written through it
 * would carry a `type` discriminator alongside its columns — a field with no column behind it,
 * in a format whose whole claim is that it mirrors the schema. Rows are always encoded against
 * their concrete serializer instead; `ExportFormat.encodeRow` is the one `when` that does the
 * dispatch, and it is exhaustive because this interface is sealed.
 */
sealed interface BackupRow

/** A row of `muscle_group`. Reference data, exported so a restore does not depend on the seed. */
@Serializable
data class MuscleGroupRow(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("icon") val icon: String? = null,
) : BackupRow

/** A row of `tag`: the six seeded system tags, and any the user added. */
@Serializable
data class TagRow(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("color") val color: String? = null,
    @SerialName("is_system") val isSystem: Boolean = false,
) : BackupRow

/** A row of `exercise`: the seeded catalogue and the user's own movements alike. */
@Serializable
data class ExerciseRow(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("exercise_type") val exerciseType: String = "STRENGTH",
    @SerialName("discipline") val discipline: String? = null,
    @SerialName("equipment_type") val equipmentType: String? = null,
    @SerialName("machine_brand") val machineBrand: String? = null,
    @SerialName("mechanic_type") val mechanicType: String? = null,
    @SerialName("difficulty_level") val difficultyLevel: String? = null,
    @SerialName("is_unilateral") val isUnilateral: Boolean = false,
    @SerialName("default_rest") val defaultRest: Long? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("instructions") val instructions: String? = null,
    @SerialName("is_custom") val isCustom: Boolean = false,
    @SerialName("icon") val icon: String? = null,
) : BackupRow

/** A row of `exercise_muscle_group`. Composite key; no surrogate id to carry. */
@Serializable
data class ExerciseMuscleGroupRow(
    @SerialName("exercise_id") val exerciseId: Long,
    @SerialName("muscle_group_id") val muscleGroupId: Long,
    @SerialName("is_primary") val isPrimary: Boolean = true,
) : BackupRow

/** A row of `exercise_tag`. Composite key. */
@Serializable
data class ExerciseTagRow(
    @SerialName("exercise_id") val exerciseId: Long,
    @SerialName("tag_id") val tagId: Long,
) : BackupRow

/** A row of `workout_template`. */
@Serializable
data class WorkoutTemplateRow(
    @SerialName("id") val id: Long,
    @SerialName("name") val name: String,
    @SerialName("category") val category: String? = null,
    @SerialName("icon") val icon: String? = null,
) : BackupRow

/** A row of `template_exercise`: which movement, in what order, and in which superset. */
@Serializable
data class TemplateExerciseRow(
    @SerialName("id") val id: Long,
    @SerialName("template_id") val templateId: Long,
    @SerialName("exercise_id") val exerciseId: Long,
    @SerialName("position") val position: Int,
    @SerialName("superset_group") val supersetGroup: Int? = null,
    @SerialName("notes") val notes: String? = null,
) : BackupRow

/** A row of `template_set`: one planned set of one templated exercise. */
@Serializable
data class TemplateSetRow(
    @SerialName("id") val id: Long,
    @SerialName("template_exercise_id") val templateExerciseId: Long,
    @SerialName("set_number") val setNumber: Int,
    @SerialName("set_type") val setType: String = "NORMAL",
    @SerialName("reps") val reps: Int? = null,
    @SerialName("weight") val weight: Long? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("distance") val distance: Long? = null,
) : BackupRow

/**
 * A row of `workout_session`.
 *
 * A session with `is_in_progress = 1` is exported like any other. The *draft* of a workout in
 * progress is not — see [ExportDocument] — but the rows it overlaps are history the moment
 * they are written, and dropping them would lose sets the user had logged.
 */
@Serializable
data class WorkoutSessionRow(
    @SerialName("id") val id: Long,
    @SerialName("template_id") val templateId: Long? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("completed_at") val completedAt: Long? = null,
    @SerialName("is_in_progress") val isInProgress: Boolean = true,
    @SerialName("last_active_at") val lastActiveAt: Long,
    @SerialName("notes") val notes: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) : BackupRow

/** A row of `session_exercise`. */
@Serializable
data class SessionExerciseRow(
    @SerialName("id") val id: Long,
    @SerialName("session_id") val sessionId: Long,
    @SerialName("exercise_id") val exerciseId: Long,
    @SerialName("position") val position: Int,
    @SerialName("superset_group") val supersetGroup: Int? = null,
    @SerialName("template_exercise_id") val templateExerciseId: Long? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) : BackupRow

/**
 * A row of `workout_set`, the widest table in the schema and the one an export is mostly
 * made of — 50,000 of these at the scale `LargeHistoryBenchmarkTest` targets.
 */
@Serializable
data class WorkoutSetRow(
    @SerialName("id") val id: Long,
    @SerialName("session_exercise_id") val sessionExerciseId: Long,
    @SerialName("set_number") val setNumber: Int,
    @SerialName("reps") val reps: Int? = null,
    @SerialName("weight") val weight: Long? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("distance") val distance: Long? = null,
    @SerialName("heart_rate_avg") val heartRateAvg: Int? = null,
    @SerialName("heart_rate_max") val heartRateMax: Int? = null,
    @SerialName("rest_after") val restAfter: Long? = null,
    @SerialName("rpe") val rpe: Int? = null,
    @SerialName("set_type") val setType: String = "NORMAL",
    @SerialName("completed_at") val completedAt: Long,
    @SerialName("is_completed") val isCompleted: Boolean = false,
    @SerialName("notes") val notes: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) : BackupRow

/**
 * A row of `personal_record`.
 *
 * docs/PERSISTENCE.md says an export *may* omit these because the PR engine can recompute them
 * from history. This format carries them anyway, and the reason is in docs/export-schema.md:
 * re-derivation produces a plausible PR set rather than *the user's*, since the detection rules
 * can change between the build that wrote the file and the one that reads it.
 */
@Serializable
data class PersonalRecordRow(
    @SerialName("id") val id: Long,
    @SerialName("exercise_id") val exerciseId: Long,
    @SerialName("workout_set_id") val workoutSetId: Long,
    @SerialName("pr_type") val prType: String,
    @SerialName("weight") val weight: Long? = null,
    @SerialName("reps") val reps: Int? = null,
    @SerialName("distance") val distance: Long? = null,
    @SerialName("duration") val duration: Long? = null,
    @SerialName("estimated_1rm") val estimated1Rm: Long? = null,
    @SerialName("achieved_at") val achievedAt: Long,
) : BackupRow

/** A row of `body_measurement`. Every measurement on it is nullable, `weight` included. */
@Serializable
data class BodyMeasurementRow(
    @SerialName("id") val id: Long,
    @SerialName("weight") val weight: Long? = null,
    @SerialName("chest") val chest: Int? = null,
    @SerialName("waist") val waist: Int? = null,
    @SerialName("arm") val arm: Int? = null,
    @SerialName("thigh") val thigh: Int? = null,
    @SerialName("body_fat") val bodyFat: Int? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
) : BackupRow

/**
 * A row of `session_xp_claim`: a marker with no payload, and its existence is the whole value.
 *
 * A restore that dropped these would re-open the double-pay window for every session a cascade
 * later re-runs over — which is exactly what the second round-trip test asserts it does not.
 */
@Serializable
data class SessionXpClaimRow(@SerialName("session_id") val sessionId: Long) : BackupRow

/** A row of `session_loot_claim`: the same marker over the drop-table roll. */
@Serializable
data class SessionLootClaimRow(@SerialName("session_id") val sessionId: Long) : BackupRow

/**
 * A row of `session_pet_claim`: the marker that a session has already grown the active pet.
 * Exported for the reason the other two claims are — without it a restored session would grow
 * the pet again the next time its cascade ran.
 */
@Serializable
data class SessionPetClaimRow(@SerialName("session_id") val sessionId: Long) : BackupRow

/**
 * The single row of `user_progress`, or no row at all before the first XP award.
 *
 * [id] is carried even though it can only ever be `1`: the column exists, the table's `CHECK`
 * pins it, and a format that omitted it would be a format that disagreed with the schema it
 * claims to mirror. The importer refuses a document that holds more than one of these, or one
 * whose id is not 1, rather than letting SQLite's `CHECK` fail with an untyped error.
 *
 * [weekSessions] is nullable for the one reason a column of this format ever is without the
 * table's being: telling a file from before the weekly streak apart from one that holds zero —
 * see [com.titan.core.domain.backup.WeeklyStreakUpgrade].
 */
@Serializable
data class UserProgressRow(
    @SerialName("id") val id: Int,
    @SerialName("total_xp") val totalXp: Long,
    @SerialName("current_level") val currentLevel: Int,
    @SerialName("current_streak") val currentStreak: Int,
    @SerialName("longest_streak") val longestStreak: Int,
    @SerialName("last_counted_at") val lastCountedAt: Long? = null,
    // Null only in a file written before the streak was weekly, which had no such column;
    // [com.titan.core.domain.backup.WeeklyStreakUpgrade] fills it before anything else reads it.
    @SerialName("week_sessions") val weekSessions: Int? = null,
    @SerialName("week_goal_met") val weekGoalMet: Boolean = false,
    @SerialName("protection_remaining") val protectionRemaining: Int,
    @SerialName("protection_period") val protectionPeriod: String? = null,
    @SerialName("protection_consumed_at") val protectionConsumedAt: Long? = null,
) : BackupRow {

    companion object {

        /**
         * The only id this table's `CHECK` admits.
         *
         * A second copy of `UserProgressEntity.SINGLE_ROW_ID`, which lives in `:core:data`
         * where the domain cannot reach it. Duplicating a constant is worth less than the
         * alternative here: the importer has to refuse a bad id *before* the insert, in the
         * layer that has no Room on its classpath, and `ExportSchemaTest` reads the constraint
         * out of the exported schema and holds the two to one value.
         */
        const val SINGLE_ROW_ID: Int = 1
    }
}

/**
 * A row of `achievement`.
 *
 * [achievementId] is *not* validated against this build's catalogue. A row naming a milestone
 * this version does not recognise is an unlock from a newer one, and docs/PERSISTENCE.md is
 * explicit that a restore must preserve it.
 */
@Serializable
data class AchievementRow(
    @SerialName("achievement_id") val achievementId: String,
    @SerialName("unlocked_at") val unlockedAt: Long,
) : BackupRow

/** A row of `quest_completion`, keyed by the quest and the period it was completed in. */
@Serializable
data class QuestCompletionRow(
    @SerialName("quest_id") val questId: String,
    @SerialName("period_start") val periodStart: Long,
    @SerialName("completed_at") val completedAt: Long,
) : BackupRow

/**
 * A row of `collected_item`: one drop, not one collectible.
 *
 * The inventory is a bag rather than a set, so two rows naming the same [collectibleId] are two
 * things the user holds and the importer must not collapse them. Neither [collectibleId] nor
 * [source] is validated against this build, for `achievement`'s reason.
 */
@Serializable
data class CollectedItemRow(
    @SerialName("id") val id: Long,
    @SerialName("collectible_id") val collectibleId: String,
    @SerialName("source") val source: String,
    @SerialName("acquired_at") val acquiredAt: Long,
    @SerialName("session_id") val sessionId: Long? = null,
) : BackupRow

/**
 * A row of `pet`: one hatched titan.
 *
 * [speciesId] is not validated against this build, for `achievement`'s reason: a species this
 * build does not know is a pet from a newer version, and it must survive the round trip.
 * [hatchedFromItemId] must resolve to a `collected_item` in the same document, and no two pets
 * may name the same one — an egg hatches once.
 */
@Serializable
data class PetRow(
    @SerialName("id") val id: Long,
    @SerialName("species_id") val speciesId: String,
    @SerialName("hatched_from_item_id") val hatchedFromItemId: Long,
    @SerialName("hatched_at") val hatchedAt: Long,
    @SerialName("xp") val xp: Long,
    @SerialName("strength") val strength: Int,
    @SerialName("endurance") val endurance: Int,
    @SerialName("mobility") val mobility: Int,
    @SerialName("nickname") val nickname: String?,
    @SerialName("is_active") val isActive: Boolean,
) : BackupRow

/**
 * A row of `pet_state`: a pet's vitals and what it is doing, as of [updatedAt].
 *
 * [activity] and [resumeActivity] are not validated against this build's activities, for the
 * reason `species_id` is not; an unknown one reads as idle. Vitals out of range are clamped on
 * the way in and again by every reader.
 */
@Serializable
data class PetStateRow(
    @SerialName("pet_id") val petId: Long,
    @SerialName("fuel") val fuel: Int,
    @SerialName("energy") val energy: Int,
    @SerialName("spirit") val spirit: Int,
    @SerialName("activity") val activity: String,
    @SerialName("activity_started_at") val activityStartedAt: Long,
    @SerialName("activity_ends_at") val activityEndsAt: Long?,
    @SerialName("resume_activity") val resumeActivity: String?,
    @SerialName("updated_at") val updatedAt: Long,
) : BackupRow

/**
 * A row of `pet_feed`: one food drop eaten. [collectedItemId] is unique — an item is eaten once —
 * and must name a `collected_item` in the same document. [petId] is null when the pet that ate
 * it is gone.
 */
@Serializable
data class PetFeedRow(
    @SerialName("collected_item_id") val collectedItemId: Long,
    @SerialName("pet_id") val petId: Long?,
    @SerialName("fed_at") val fedAt: Long,
) : BackupRow

/**
 * A row of `food_seen`: the newest drop of a food the user has looked at, which is what decides
 * whether the food reads as new. Not validated against this build's foods.
 */
@Serializable
data class FoodSeenRow(
    @SerialName("collectible_id") val collectibleId: String,
    @SerialName("seen_up_to") val seenUpTo: Long,
) : BackupRow

/** A row of `pet_unlock`: something unlocked for the titan, kept once earned. Not validated. */
@Serializable
data class PetUnlockRow(
    @SerialName("key") val key: String,
    @SerialName("unlocked_at") val unlockedAt: Long,
) : BackupRow
