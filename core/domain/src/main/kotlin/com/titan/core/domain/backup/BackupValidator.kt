package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.ExerciseRow
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.backup.model.UserProgressRow
import com.titan.core.domain.model.WorkoutSet

/**
 * Checks a parsed [ExportDocument] against the constraints the database would enforce, *before*
 * anything is wiped.
 *
 * ### Why this can be done at all
 *
 * Because import replaces rather than merges. The document is the whole database afterwards, so
 * every foreign key in it has to resolve *inside* it — which makes referential integrity a
 * property of the file alone, checkable with nothing but the file. A merging importer could not
 * do this: whether `workout_set` 42's parent exists would depend on rows already on the device.
 *
 * That property is what pays for the acceptance criterion behind it. A dangling reference is
 * caught while the database is still untouched, so [ImportError.ConstraintViolation] means "and
 * nothing happened" rather than "and we rolled back, probably". The import transaction is still
 * there and still catches what this does not model — see
 * `com.titan.core.domain.repository.BackupRepository.replaceAll` — but it is the backstop,
 * not the mechanism.
 *
 * ### What is checked, and what deliberately is not
 *
 * Checked: everything SQLite itself would refuse, which is the set of failures that would
 * otherwise arrive as an untyped exception — every foreign key, every primary key, every unique
 * index, and the `CHECK` that pins `user_progress` to one row.
 *
 * Also checked: the ranges the format itself defines, which no build of the app has ever
 * written outside of — row ids in `1..MAX_ROW_ID`, timestamps in `0..MAX_TIMESTAMP`, a set's
 * RPE in `WorkoutSet.RPE_MIN..RPE_MAX` and its `set_number` from 1. A value outside them is not
 * a record of anything; it is a file somebody else wrote, and each one breaks the app rather
 * than reading oddly. An id of `Long.MAX_VALUE` leaves `AUTOINCREMENT` nothing to assign, so
 * every later insert fails; an id of zero or below is one the draft of a workout refuses to be
 * built around; a session dated before 1970 opens an all-time chart of millions of weeks.
 *
 * And two rules of the same kind that are about shape rather than range:
 *
 * - **At most one session in progress.** The app holds that invariant on every start
 *   (`StartSessionUseCase`), and an export only ever carries the one it had. A file with two
 *   would restore a device where resume picks one by `last_active_at` and the other stays
 *   open, uncounted and unfinishable, for good.
 * - **Well-formed text in every text column a unique key covers.** This compares keys as
 *   Kotlin strings, which are UTF-16, and SQLite's index compares the UTF-8 it stores. For
 *   well-formed text the two agree, since the encodings map one to one. For a string holding
 *   an unpaired surrogate — which JSON can spell as `\ud800` — they do not: SQLite's own
 *   conversion pairs a lone high surrogate with whatever follows it, so two names that differ
 *   here can be one name there, pass this check and then fail the insert. Refusing the
 *   ill-formed string is what keeps the check and the index agreeing.
 *
 * Not checked: every invariant docs/PERSISTENCE.md says lives in the domain rather than the
 * schema — `SetValidator`'s value bounds, dense set numbering, the superset rules,
 * `BodyMeasurementValidator`'s all-null rule, and whether an enum-like column holds a name this
 * build recognises. Those are not skipped out of laziness. A backup is *the user's own data
 * coming back*, and PERSISTENCE.md is explicit that a row naming a collectible or an
 * achievement from a newer version is a record rather than a corruption, which a restore must
 * preserve. Refusing a file because a set carries a weight this build's `SetValidator` would
 * not accept would make an export unrestorable on the very build that wrote it, the day a bound
 * moved. The readers degrade on such a value instead — see docs/export-schema.md, which states
 * this as the format's rule rather than leaving it here.
 */
object BackupValidator {

    /**
     * The first constraint [document] breaks, or **null** if it describes a database SQLite
     * would accept.
     *
     * First rather than all: a document with one dangling reference usually has thousands of
     * them — a truncated file loses the tail of every table — and a screen shows one sentence.
     * The message names the table, the column, the offending value and what it pointed at, so
     * it is worth putting in a bug report as it stands.
     *
     * The checks are grouped by the part of the schema they cover and chained with elvis, so
     * the first failure short-circuits the rest: nothing after it is evaluated, including the
     * id sets it would have built — which is why those are `by lazy` rather than locals. At the
     * scale the app targets, `workoutSets` alone is a fifty-thousand-entry set built for one
     * check that a malformed document usually never reaches.
     */
    fun validate(document: ExportDocument): ImportError.ConstraintViolation? =
        catalogueConstraints(document)
            ?: templateConstraints(document)
            ?: historyConstraints(document)
            ?: gamificationConstraints(document)
            ?: rowIdRanges(document)
            ?: timestampRanges(document)
            ?: setRanges(document)
            ?: sessionsInProgress(document)
            ?: textKeys(document)

    /**
     * The largest row id a file may carry: 2^53.
     *
     * `AUTOINCREMENT` numbers the next row one past the largest id the table has ever held, so
     * an id near `Long.MAX_VALUE` leaves it nothing to assign and every later insert fails with
     * `SQLITE_FULL` — and the counter survives a wipe, so deleting the rows does not help. 2^53
     * is far above any id a device numbers in its life and far below that ceiling, and it is
     * also the largest integer a JSON reader that parses numbers as doubles reads exactly.
     */
    const val MAX_ROW_ID: Long = 9_007_199_254_740_992L

    /**
     * The latest timestamp a file may carry: the last millisecond of the year 9999, UTC.
     *
     * The earliest is 0, the epoch. `workout_set.completed_at` holds 0 as the placeholder of a
     * set that has not been performed, and nothing in the app is dated earlier.
     */
    const val MAX_TIMESTAMP: Long = 253_402_300_799_999L

    /** `muscle_group`, `tag`, `exercise` and the two join tables that hang off them. */
    private fun catalogueConstraints(document: ExportDocument): ImportError.ConstraintViolation? {
        val exercises by lazy { document.exercise.mapTo(mutableSetOf()) { it.id } }
        val muscleGroups by lazy { document.muscleGroup.mapTo(mutableSetOf()) { it.id } }
        val tags by lazy { document.tag.mapTo(mutableSetOf()) { it.id } }

        return unique(BackupTable.MUSCLE_GROUP, "id", document.muscleGroup.map { it.id })
            ?: unique(BackupTable.MUSCLE_GROUP, "name", document.muscleGroup.map { it.name })
            ?: unique(BackupTable.TAG, "id", document.tag.map { it.id })
            ?: unique(BackupTable.TAG, "name", document.tag.map { it.name })
            ?: unique(BackupTable.EXERCISE, "id", document.exercise.map { it.id })
            ?: unique(BackupTable.EXERCISE, EXERCISE_NATURAL_KEY, document.exercise.map(::exerciseNaturalKey))
            ?: unique(
                BackupTable.EXERCISE_MUSCLE_GROUP,
                "id pair",
                document.exerciseMuscleGroup.map { it.exerciseId to it.muscleGroupId },
            )
            ?: unique(BackupTable.EXERCISE_TAG, "id pair", document.exerciseTag.map { it.exerciseId to it.tagId })
            ?: references(
                EXERCISE_MUSCLE_GROUP_TO_EXERCISE,
                exercises,
                document.exerciseMuscleGroup.map { it.exerciseId },
            )
            ?: references(
                EXERCISE_MUSCLE_GROUP_TO_GROUP,
                muscleGroups,
                document.exerciseMuscleGroup.map { it.muscleGroupId },
            )
            ?: references(EXERCISE_TAG_TO_EXERCISE, exercises, document.exerciseTag.map { it.exerciseId })
            ?: references(EXERCISE_TAG_TO_TAG, tags, document.exerciseTag.map { it.tagId })
    }

    /** `workout_template`, `template_exercise`, `template_set`. */
    private fun templateConstraints(document: ExportDocument): ImportError.ConstraintViolation? {
        val templates by lazy { document.workoutTemplate.mapTo(mutableSetOf()) { it.id } }
        val exercises by lazy { document.exercise.mapTo(mutableSetOf()) { it.id } }
        val templateExercises by lazy { document.templateExercise.mapTo(mutableSetOf()) { it.id } }

        return unique(BackupTable.WORKOUT_TEMPLATE, "id", document.workoutTemplate.map { it.id })
            ?: unique(BackupTable.TEMPLATE_EXERCISE, "id", document.templateExercise.map { it.id })
            ?: unique(
                BackupTable.TEMPLATE_EXERCISE,
                "(template_id, position)",
                document.templateExercise.map { it.templateId to it.position },
            )
            ?: unique(BackupTable.TEMPLATE_SET, "id", document.templateSet.map { it.id })
            ?: unique(
                BackupTable.TEMPLATE_SET,
                "(template_exercise_id, set_number)",
                document.templateSet.map { it.templateExerciseId to it.setNumber },
            )
            ?: references(TEMPLATE_EXERCISE_TO_TEMPLATE, templates, document.templateExercise.map { it.templateId })
            ?: references(TEMPLATE_EXERCISE_TO_EXERCISE, exercises, document.templateExercise.map { it.exerciseId })
            ?: references(
                TEMPLATE_SET_TO_TEMPLATE_EXERCISE,
                templateExercises,
                document.templateSet.map { it.templateExerciseId },
            )
    }

    /** `workout_session`, `session_exercise`, `workout_set`, `personal_record`. */
    private fun historyConstraints(document: ExportDocument): ImportError.ConstraintViolation? {
        val templates by lazy { document.workoutTemplate.mapTo(mutableSetOf()) { it.id } }
        val exercises by lazy { document.exercise.mapTo(mutableSetOf()) { it.id } }
        val templateExercises by lazy { document.templateExercise.mapTo(mutableSetOf()) { it.id } }
        val sessions by lazy { document.workoutSession.mapTo(mutableSetOf()) { it.id } }
        val sessionExercises by lazy { document.sessionExercise.mapTo(mutableSetOf()) { it.id } }
        val workoutSets by lazy { document.workoutSet.mapTo(mutableSetOf()) { it.id } }

        return unique(BackupTable.WORKOUT_SESSION, "id", document.workoutSession.map { it.id })
            ?: unique(BackupTable.SESSION_EXERCISE, "id", document.sessionExercise.map { it.id })
            ?: unique(
                BackupTable.SESSION_EXERCISE,
                "(session_id, position)",
                document.sessionExercise.map { it.sessionId to it.position },
            )
            ?: unique(BackupTable.WORKOUT_SET, "id", document.workoutSet.map { it.id })
            ?: unique(BackupTable.PERSONAL_RECORD, "id", document.personalRecord.map { it.id })
            ?: references(SESSION_TO_TEMPLATE, templates, document.workoutSession.map { it.templateId })
            ?: references(SESSION_EXERCISE_TO_SESSION, sessions, document.sessionExercise.map { it.sessionId })
            ?: references(SESSION_EXERCISE_TO_EXERCISE, exercises, document.sessionExercise.map { it.exerciseId })
            ?: references(
                SESSION_EXERCISE_TO_TEMPLATE_EXERCISE,
                templateExercises,
                document.sessionExercise.map { it.templateExerciseId },
            )
            ?: references(
                WORKOUT_SET_TO_SESSION_EXERCISE,
                sessionExercises,
                document.workoutSet.map { it.sessionExerciseId },
            )
            ?: references(PERSONAL_RECORD_TO_EXERCISE, exercises, document.personalRecord.map { it.exerciseId })
            ?: references(PERSONAL_RECORD_TO_WORKOUT_SET, workoutSets, document.personalRecord.map { it.workoutSetId })
    }

    /** `body_measurement`, the two claim tables, and everything the gamification group holds. */
    private fun gamificationConstraints(document: ExportDocument): ImportError.ConstraintViolation? {
        val sessions by lazy { document.workoutSession.mapTo(mutableSetOf()) { it.id } }

        return unique(BackupTable.BODY_MEASUREMENT, "id", document.bodyMeasurement.map { it.id })
            ?: unique(BackupTable.SESSION_XP_CLAIM, "session_id", document.sessionXpClaim.map { it.sessionId })
            ?: unique(BackupTable.SESSION_LOOT_CLAIM, "session_id", document.sessionLootClaim.map { it.sessionId })
            ?: unique(BackupTable.SESSION_PET_CLAIM, "session_id", document.sessionPetClaim.map { it.sessionId })
            ?: unique(BackupTable.ACHIEVEMENT, "achievement_id", document.achievement.map { it.achievementId })
            ?: unique(
                BackupTable.QUEST_COMPLETION,
                "(quest_id, period_start)",
                document.questCompletion.map { it.questId to it.periodStart },
            )
            ?: unique(BackupTable.COLLECTED_ITEM, "id", document.collectedItem.map { it.id })
            ?: singleRowProgress(document.userProgress)
            ?: references(XP_CLAIM_TO_SESSION, sessions, document.sessionXpClaim.map { it.sessionId })
            ?: references(LOOT_CLAIM_TO_SESSION, sessions, document.sessionLootClaim.map { it.sessionId })
            ?: references(PET_CLAIM_TO_SESSION, sessions, document.sessionPetClaim.map { it.sessionId })
            ?: references(COLLECTED_ITEM_TO_SESSION, sessions, document.collectedItem.map { it.sessionId })
            ?: petConstraints(document)
    }

    /**
     * What the `pet` table's key, its unique index and its foreign key will demand.
     *
     * The unique `hatched_from_item_id` is what makes an egg hatch once, so a file naming one egg
     * from two pets is refused here, before the wipe, rather than by SQLite after it.
     */
    private fun petConstraints(document: ExportDocument): ImportError.ConstraintViolation? {
        val collectedItems by lazy { document.collectedItem.mapTo(mutableSetOf()) { it.id } }

        return unique(BackupTable.PET, "id", document.pet.map { it.id })
            ?: unique(BackupTable.PET, "hatched_from_item_id", document.pet.map { it.hatchedFromItemId })
            ?: references(PET_TO_COLLECTED_ITEM, collectedItems, document.pet.map { it.hatchedFromItemId })
            ?: unique(BackupTable.PET_FEED, "collected_item_id", document.petFeed.map { it.collectedItemId })
            ?: references(PET_FEED_TO_COLLECTED_ITEM, collectedItems, document.petFeed.map { it.collectedItemId })
            ?: references(
                PET_FEED_TO_PET,
                document.pet.mapTo(mutableSetOf()) { it.id },
                document.petFeed.map { it.petId },
            )
            ?: unique(BackupTable.FOOD_SEEN, "collectible_id", document.foodSeen.map { it.collectibleId })
            ?: unique(BackupTable.PET_UNLOCK, "key", document.petUnlock.map { it.key })
            ?: unique(BackupTable.PET_LOADOUT, "(pet_id, slot)", document.petLoadout.map { it.petId to it.slot })
            ?: references(
                PET_LOADOUT_TO_PET,
                document.pet.mapTo(mutableSetOf()) { it.id },
                document.petLoadout.map { it.petId },
            )
            ?: sparConstraints(document)
            ?: unique(BackupTable.PET_STATE, "pet_id", document.petState.map { it.petId })
            ?: references(
                PET_STATE_TO_PET,
                document.pet.mapTo(mutableSetOf()) { it.id },
                document.petState.map { it.petId },
            )
    }

    /** Every autogenerated `id`, inside `1..MAX_ROW_ID`. Foreign keys follow, since they must resolve. */
    private fun sparConstraints(document: ExportDocument): ImportError.ConstraintViolation? {
        val pets by lazy { document.pet.mapTo(mutableSetOf()) { it.id } }
        return unique(
            BackupTable.PET_POWER_SNAPSHOT,
            "(pet_id, week_start)",
            document.petPowerSnapshot.map { it.petId to it.weekStart },
        )
            ?: references(SNAPSHOT_TO_PET, pets, document.petPowerSnapshot.map { it.petId })
            ?: unique(BackupTable.PET_SPAR, "id", document.petSpar.map { it.id })
            ?: references(SPAR_TO_PET, pets, document.petSpar.map { it.petId })
    }

    private fun rowIdRanges(document: ExportDocument): ImportError.ConstraintViolation? =
        firstOutside(
            1..MAX_ROW_ID,
            Column(BackupTable.MUSCLE_GROUP, "id") { document.muscleGroup.map { it.id } },
            Column(BackupTable.TAG, "id") { document.tag.map { it.id } },
            Column(BackupTable.EXERCISE, "id") { document.exercise.map { it.id } },
            Column(BackupTable.WORKOUT_TEMPLATE, "id") { document.workoutTemplate.map { it.id } },
            Column(BackupTable.TEMPLATE_EXERCISE, "id") { document.templateExercise.map { it.id } },
            Column(BackupTable.TEMPLATE_SET, "id") { document.templateSet.map { it.id } },
            Column(BackupTable.WORKOUT_SESSION, "id") { document.workoutSession.map { it.id } },
            Column(BackupTable.SESSION_EXERCISE, "id") { document.sessionExercise.map { it.id } },
            Column(BackupTable.WORKOUT_SET, "id") { document.workoutSet.map { it.id } },
            Column(BackupTable.PERSONAL_RECORD, "id") { document.personalRecord.map { it.id } },
            Column(BackupTable.BODY_MEASUREMENT, "id") { document.bodyMeasurement.map { it.id } },
            Column(BackupTable.COLLECTED_ITEM, "id") { document.collectedItem.map { it.id } },
            Column(BackupTable.PET, "id") { document.pet.map { it.id } },
            Column(BackupTable.PET_SPAR, "id") { document.petSpar.map { it.id } },
        )

    /** Every timestamp column, inside `0..MAX_TIMESTAMP`. */
    private fun timestampRanges(document: ExportDocument): ImportError.ConstraintViolation? {
        val sessions = document.workoutSession
        val sessionExercises = document.sessionExercise
        val sets = document.workoutSet
        val measurements = document.bodyMeasurement
        val progress = document.userProgress
        val quests = document.questCompletion
        return firstOutside(
            0..MAX_TIMESTAMP,
            Column(BackupTable.WORKOUT_SESSION, "completed_at") { sessions.map { it.completedAt } },
            Column(BackupTable.WORKOUT_SESSION, "last_active_at") { sessions.map { it.lastActiveAt } },
            Column(BackupTable.WORKOUT_SESSION, "created_at") { sessions.map { it.createdAt } },
            Column(BackupTable.WORKOUT_SESSION, "updated_at") { sessions.map { it.updatedAt } },
            Column(BackupTable.SESSION_EXERCISE, "created_at") { sessionExercises.map { it.createdAt } },
            Column(BackupTable.SESSION_EXERCISE, "updated_at") { sessionExercises.map { it.updatedAt } },
            Column(BackupTable.WORKOUT_SET, "completed_at") { sets.map { it.completedAt } },
            Column(BackupTable.WORKOUT_SET, "created_at") { sets.map { it.createdAt } },
            Column(BackupTable.WORKOUT_SET, "updated_at") { sets.map { it.updatedAt } },
            Column(BackupTable.PERSONAL_RECORD, "achieved_at") { document.personalRecord.map { it.achievedAt } },
            Column(BackupTable.BODY_MEASUREMENT, "created_at") { measurements.map { it.createdAt } },
            Column(BackupTable.BODY_MEASUREMENT, "updated_at") { measurements.map { it.updatedAt } },
            Column(BackupTable.USER_PROGRESS, "last_counted_at") { progress.map { it.lastCountedAt } },
            Column(BackupTable.USER_PROGRESS, "protection_consumed_at") { progress.map { it.protectionConsumedAt } },
            Column(BackupTable.ACHIEVEMENT, "unlocked_at") { document.achievement.map { it.unlockedAt } },
            Column(BackupTable.QUEST_COMPLETION, "period_start") { quests.map { it.periodStart } },
            Column(BackupTable.QUEST_COMPLETION, "completed_at") { quests.map { it.completedAt } },
            Column(BackupTable.COLLECTED_ITEM, "acquired_at") { document.collectedItem.map { it.acquiredAt } },
            Column(BackupTable.PET, "hatched_at") { document.pet.map { it.hatchedAt } },
            Column(BackupTable.PET_FEED, "fed_at") { document.petFeed.map { it.fedAt } },
            Column(BackupTable.PET_SPAR, "fought_at") { document.petSpar.map { it.foughtAt } },
            Column(BackupTable.PET_UNLOCK, "unlocked_at") { document.petUnlock.map { it.unlockedAt } },
            Column(BackupTable.PET_STATE, "activity_started_at") { document.petState.map { it.activityStartedAt } },
            Column(BackupTable.PET_STATE, "activity_ends_at") { document.petState.map { it.activityEndsAt } },
            Column(BackupTable.PET_STATE, "updated_at") { document.petState.map { it.updatedAt } },
        )
    }

    /** `set_number` from 1 on both set tables, and every present `workout_set.rpe` on the format's scale. */
    private fun setRanges(document: ExportDocument): ImportError.ConstraintViolation? =
        firstOutside(
            1L..Int.MAX_VALUE,
            Column(BackupTable.TEMPLATE_SET, "set_number") { document.templateSet.map { it.setNumber.toLong() } },
            Column(BackupTable.WORKOUT_SET, "set_number") { document.workoutSet.map { it.setNumber.toLong() } },
        ) ?: firstOutside(
            WorkoutSet.RPE_MIN.toLong()..WorkoutSet.RPE_MAX,
            Column(BackupTable.WORKOUT_SET, "rpe") { document.workoutSet.map { it.rpe?.toLong() } },
        )

    /** No more than one `workout_session` with `is_in_progress` set. */
    private fun sessionsInProgress(document: ExportDocument): ImportError.ConstraintViolation? {
        val inProgress = document.workoutSession.filter { it.isInProgress }
        if (inProgress.size <= 1) return null
        return ImportError.ConstraintViolation(
            "workout_session holds ${inProgress.size} sessions in progress, ids " +
                "${inProgress[0].id} and ${inProgress[1].id} among them; at most one may be",
        )
    }

    /**
     * Every text column a primary key or unique index covers, free of unpaired surrogates.
     *
     * A nullable column's null is skipped: it is the absence of text, not ill-formed text.
     */
    private fun textKeys(document: ExportDocument): ImportError.ConstraintViolation? {
        val exercises = document.exercise
        return listOf(
            TextColumn(BackupTable.MUSCLE_GROUP, "name") { document.muscleGroup.map { it.name } },
            TextColumn(BackupTable.TAG, "name") { document.tag.map { it.name } },
            TextColumn(BackupTable.EXERCISE, "name") { exercises.map { it.name } },
            TextColumn(BackupTable.EXERCISE, "equipment_type") { exercises.map { it.equipmentType } },
            TextColumn(BackupTable.EXERCISE, "machine_brand") { exercises.map { it.machineBrand } },
            TextColumn(BackupTable.ACHIEVEMENT, "achievement_id") { document.achievement.map { it.achievementId } },
            TextColumn(BackupTable.QUEST_COMPLETION, "quest_id") { document.questCompletion.map { it.questId } },
        ).firstNotNullOfOrNull { column ->
            val row = column.values().indexOfFirst { it != null && !it.isWellFormedUtf16() }
            if (row < 0) {
                null
            } else {
                // The value itself is left out of the message: it is the one string in the file
                // known not to be printable, and a log line is where it would be printed.
                ImportError.ConstraintViolation(
                    "${column.table.tableName}.${column.name} of row ${row + 1} holds an unpaired " +
                        "surrogate, which SQLite would not store as written",
                )
            }
        }
    }

    /**
     * Whether every surrogate in this string is half of a pair, which is what makes its UTF-8
     * encoding — the form SQLite stores and compares — a faithful copy of it.
     */
    private fun String.isWellFormedUtf16(): Boolean {
        var index = 0
        while (index < length) {
            val char = this[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                    index += 2
                }

                char.isLowSurrogate() -> return false

                else -> index++
            }
        }
        return true
    }

    /**
     * The first value in [columns] outside [range], as a violation naming its column, or null.
     *
     * The columns are read in order, and only up to the first one that fails, for the reason
     * [validate] chains its groups: a column's values are a list as long as its table.
     */
    private fun firstOutside(
        range: LongRange,
        vararg columns: Column,
    ): ImportError.ConstraintViolation? =
        columns.firstNotNullOfOrNull { column -> inRange(column.table, column.name, range, column.values()) }

    /**
     * Refuses [values] if one of them is outside [range].
     *
     * A **null** value is not a violation: it is a nullable column saying the row holds nothing
     * there, which every range below allows.
     */
    private fun inRange(
        table: BackupTable,
        column: String,
        range: LongRange,
        values: List<Long?>,
    ): ImportError.ConstraintViolation? {
        val outside = values.firstOrNull { it != null && it !in range } ?: return null
        return ImportError.ConstraintViolation(
            "${table.tableName}.$column $outside is outside the accepted range $range",
        )
    }

    /**
     * Refuses [keys] if two of them are equal.
     *
     * A **null** key is skipped rather than compared, which is how a caller expresses SQLite's
     * "NULLs are distinct" rule for a unique index with a nullable component. `exercise` is the
     * only index in the schema that needs it — see [exerciseNaturalKey].
     */
    private fun unique(
        table: BackupTable,
        label: String,
        keys: List<Any?>,
    ): ImportError.ConstraintViolation? {
        val seen = HashSet<Any>(keys.size)
        val clash = keys.firstOrNull { it != null && !seen.add(it) } ?: return null
        return ImportError.ConstraintViolation("${table.tableName} holds two rows with the same $label $clash")
    }

    /**
     * Refuses [values] if one of them names a row [parentIds] does not hold.
     *
     * A **null** value is not a violation: it is a nullable foreign key saying the row points at
     * nothing, which every `SET NULL` column in the schema allows.
     */
    private fun references(
        edge: ForeignKey,
        parentIds: Set<Long>,
        values: List<Long?>,
    ): ImportError.ConstraintViolation? {
        val orphan = values.firstOrNull { it != null && it !in parentIds } ?: return null
        return ImportError.ConstraintViolation(
            "${edge.child.tableName}.${edge.column} $orphan names a " +
                "${edge.parent.tableName} the document does not contain",
        )
    }

    /**
     * Refuses anything but the one row `user_progress` can hold.
     *
     * The table carries a `CHECK (id = 1)` that Room cannot express and
     * `TitanDatabase.singleRowProgressCallback` applies by hand, so a document with two rows
     * in it — or one row numbered 2 — would fail at the insert with a constraint error naming
     * none of the nineteen tables it could have come from. Catching it here is what turns that
     * into a sentence.
     */
    private fun singleRowProgress(rows: List<UserProgressRow>): ImportError.ConstraintViolation? =
        when {
            rows.size > 1 ->
                ImportError.ConstraintViolation("user_progress holds ${rows.size} rows; the table admits at most one")

            rows.any { it.id != UserProgressRow.SINGLE_ROW_ID } ->
                ImportError.ConstraintViolation(
                    "user_progress.id must be ${UserProgressRow.SINGLE_ROW_ID}, was ${rows.first().id}",
                )

            else -> null
        }

    /**
     * `exercise`'s natural key, or **null** for a row the unique index cannot collide on.
     *
     * SQLite treats NULLs as distinct in a unique index, so two rows that agree on the name and
     * hold no equipment or no brand do *not* clash — docs/PERSISTENCE.md calls that out, and it
     * is what keeps a custom movement from colliding with a seeded one. Keying such a row on a
     * triple containing null would report a clash the database would have allowed, and refuse a
     * document it would have taken.
     */
    private fun exerciseNaturalKey(row: ExerciseRow): Triple<String, String, String>? {
        val equipment = row.equipmentType ?: return null
        val brand = row.machineBrand ?: return null
        return Triple(row.name, equipment, brand)
    }

    /** One numeric column of the document, and how to read its values out of it when asked. */
    private class Column(
        val table: BackupTable,
        val name: String,
        val values: () -> List<Long?>,
    )

    /** One text column of the document, read the same way as [Column]. */
    private class TextColumn(
        val table: BackupTable,
        val name: String,
        val values: () -> List<String?>,
    )

    /** One foreign key of the schema: which table points at which, through which column. */
    private data class ForeignKey(
        val child: BackupTable,
        val column: String,
        val parent: BackupTable,
    )

    private const val EXERCISE_NATURAL_KEY = "(name, equipment_type, machine_brand)"

    /*
     * Every foreign key docs/PERSISTENCE.md's referential-actions table lists, named once here
     * so the checks above read as a list of edges rather than a wall of arguments. The order
     * matches that table.
     */
    private val EXERCISE_MUSCLE_GROUP_TO_EXERCISE =
        ForeignKey(BackupTable.EXERCISE_MUSCLE_GROUP, "exercise_id", BackupTable.EXERCISE)
    private val EXERCISE_MUSCLE_GROUP_TO_GROUP =
        ForeignKey(BackupTable.EXERCISE_MUSCLE_GROUP, "muscle_group_id", BackupTable.MUSCLE_GROUP)
    private val EXERCISE_TAG_TO_EXERCISE =
        ForeignKey(BackupTable.EXERCISE_TAG, "exercise_id", BackupTable.EXERCISE)
    private val EXERCISE_TAG_TO_TAG =
        ForeignKey(BackupTable.EXERCISE_TAG, "tag_id", BackupTable.TAG)
    private val TEMPLATE_EXERCISE_TO_TEMPLATE =
        ForeignKey(BackupTable.TEMPLATE_EXERCISE, "template_id", BackupTable.WORKOUT_TEMPLATE)
    private val TEMPLATE_EXERCISE_TO_EXERCISE =
        ForeignKey(BackupTable.TEMPLATE_EXERCISE, "exercise_id", BackupTable.EXERCISE)
    private val TEMPLATE_SET_TO_TEMPLATE_EXERCISE =
        ForeignKey(BackupTable.TEMPLATE_SET, "template_exercise_id", BackupTable.TEMPLATE_EXERCISE)
    private val SESSION_TO_TEMPLATE =
        ForeignKey(BackupTable.WORKOUT_SESSION, "template_id", BackupTable.WORKOUT_TEMPLATE)
    private val SESSION_EXERCISE_TO_SESSION =
        ForeignKey(BackupTable.SESSION_EXERCISE, "session_id", BackupTable.WORKOUT_SESSION)
    private val SESSION_EXERCISE_TO_EXERCISE =
        ForeignKey(BackupTable.SESSION_EXERCISE, "exercise_id", BackupTable.EXERCISE)
    private val SESSION_EXERCISE_TO_TEMPLATE_EXERCISE =
        ForeignKey(BackupTable.SESSION_EXERCISE, "template_exercise_id", BackupTable.TEMPLATE_EXERCISE)
    private val WORKOUT_SET_TO_SESSION_EXERCISE =
        ForeignKey(BackupTable.WORKOUT_SET, "session_exercise_id", BackupTable.SESSION_EXERCISE)
    private val PERSONAL_RECORD_TO_EXERCISE =
        ForeignKey(BackupTable.PERSONAL_RECORD, "exercise_id", BackupTable.EXERCISE)
    private val PERSONAL_RECORD_TO_WORKOUT_SET =
        ForeignKey(BackupTable.PERSONAL_RECORD, "workout_set_id", BackupTable.WORKOUT_SET)
    private val XP_CLAIM_TO_SESSION =
        ForeignKey(BackupTable.SESSION_XP_CLAIM, "session_id", BackupTable.WORKOUT_SESSION)
    private val LOOT_CLAIM_TO_SESSION =
        ForeignKey(BackupTable.SESSION_LOOT_CLAIM, "session_id", BackupTable.WORKOUT_SESSION)
    private val PET_CLAIM_TO_SESSION =
        ForeignKey(BackupTable.SESSION_PET_CLAIM, "session_id", BackupTable.WORKOUT_SESSION)
    private val COLLECTED_ITEM_TO_SESSION =
        ForeignKey(BackupTable.COLLECTED_ITEM, "session_id", BackupTable.WORKOUT_SESSION)
    private val PET_FEED_TO_COLLECTED_ITEM =
        ForeignKey(BackupTable.PET_FEED, "collected_item_id", BackupTable.COLLECTED_ITEM)
    private val PET_FEED_TO_PET =
        ForeignKey(BackupTable.PET_FEED, "pet_id", BackupTable.PET)
    private val SNAPSHOT_TO_PET =
        ForeignKey(BackupTable.PET_POWER_SNAPSHOT, "pet_id", BackupTable.PET)
    private val SPAR_TO_PET =
        ForeignKey(BackupTable.PET_SPAR, "pet_id", BackupTable.PET)
    private val PET_LOADOUT_TO_PET =
        ForeignKey(BackupTable.PET_LOADOUT, "pet_id", BackupTable.PET)
    private val PET_STATE_TO_PET =
        ForeignKey(BackupTable.PET_STATE, "pet_id", BackupTable.PET)
    private val PET_TO_COLLECTED_ITEM =
        ForeignKey(BackupTable.PET, "hatched_from_item_id", BackupTable.COLLECTED_ITEM)
}
