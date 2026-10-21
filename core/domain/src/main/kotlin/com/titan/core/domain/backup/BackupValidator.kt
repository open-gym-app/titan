package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.ExerciseRow
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.backup.model.UserProgressRow

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
 * Not checked: every invariant docs/PERSISTENCE.md says lives in the domain rather than the
 * schema — `SetValidator`'s value bounds, dense set numbering, the superset rules,
 * `BodyMeasurementValidator`'s all-null rule, and whether an enum-like column holds a name this
 * build recognises. Those are not skipped out of laziness. A backup is *the user's own data
 * coming back*, and PERSISTENCE.md is explicit that a row naming a collectible or an
 * achievement from a newer version is a record rather than a corruption, which a restore must
 * preserve. Refusing a file because a set carries an RPE this build would not have written
 * would make an export unrestorable on the very build that wrote it, the day a bound moved. See
 * docs/export-schema.md, which states this as the format's rule rather than leaving it here.
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
}
