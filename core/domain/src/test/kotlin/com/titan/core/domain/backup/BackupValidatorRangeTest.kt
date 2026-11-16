package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.BodyMeasurementRow
import com.titan.core.domain.backup.model.CollectedItemRow
import com.titan.core.domain.backup.model.ExerciseRow
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.backup.model.MuscleGroupRow
import com.titan.core.domain.backup.model.PersonalRecordRow
import com.titan.core.domain.backup.model.PetRow
import com.titan.core.domain.backup.model.SessionExerciseRow
import com.titan.core.domain.backup.model.TagRow
import com.titan.core.domain.backup.model.TemplateExerciseRow
import com.titan.core.domain.backup.model.TemplateSetRow
import com.titan.core.domain.backup.model.WorkoutSessionRow
import com.titan.core.domain.backup.model.WorkoutSetRow
import com.titan.core.domain.backup.model.WorkoutTemplateRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The value ranges the format defines, each pinned at both of its edges (#256).
 *
 * docs/export-schema.md lists them under "What an import refuses". Every one of these values
 * crashed the app after a restore that let it through: an id `AUTOINCREMENT` cannot continue
 * from, an in-progress session numbered below one, a session dated before 1970. The set
 * columns are here because the format's scale is fixed and nothing reads a set numbered zero.
 */
class BackupValidatorRangeTest {

    // ─── Row ids ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the ids the format accepts run from one to two to the fifty-third`() {
        assertEquals(9_007_199_254_740_992L, BackupValidator.MAX_ROW_ID)
        ID_COLUMNS.keys.forEach { table ->
            assertNull(table.tableName, BackupValidator.validate(sampleDocument().withRowId(table, 99)))
            assertNull(
                table.tableName,
                BackupValidator.validate(sampleDocument().withRowId(table, BackupValidator.MAX_ROW_ID)),
            )
        }
    }

    /** Zero is rewritten by `nullif(?, 0)` into a fresh id and strands every reference to it. */
    @Test
    fun `an id of zero is refused in every table`() {
        ID_COLUMNS.keys.forEach { table ->
            assertRefused(
                sampleDocument().withRowId(table, 0),
                "${table.tableName}.id 0",
            )
        }
    }

    /** A session numbered −1 still in progress is one the workout draft refuses to be built around. */
    @Test
    fun `a negative id is refused in every table`() {
        ID_COLUMNS.keys.forEach { table ->
            assertRefused(
                sampleDocument().withRowId(table, -1),
                "${table.tableName}.id -1",
            )
        }
    }

    /** `Long.MAX_VALUE` leaves `AUTOINCREMENT` nothing to assign, so every later insert fails. */
    @Test
    fun `an id past two to the fifty-third is refused in every table`() {
        ID_COLUMNS.keys.forEach { table ->
            assertRefused(
                sampleDocument().withRowId(table, BackupValidator.MAX_ROW_ID + 1),
                "${table.tableName}.id ${BackupValidator.MAX_ROW_ID + 1}",
            )
            assertRefused(sampleDocument().withRowId(table, Long.MAX_VALUE), "${table.tableName}.id ${Long.MAX_VALUE}")
        }
    }

    // ─── Timestamps ─────────────────────────────────────────────────────────────────────

    /** Zero is the placeholder a planned set's `completed_at` holds, so it is the floor. */
    @Test
    fun `the timestamps the format accepts run from the epoch to the end of 9999`() {
        assertEquals(253_402_300_799_999L, BackupValidator.MAX_TIMESTAMP)
        TIMESTAMP_COLUMNS.forEach { (column, set) ->
            assertNull(column, BackupValidator.validate(set(sampleDocument(), 0)))
            assertNull(column, BackupValidator.validate(set(sampleDocument(), BackupValidator.MAX_TIMESTAMP)))
        }
    }

    /** −10¹⁷ opened an all-time chart of a week per bucket back to the year −3 million. */
    @Test
    fun `a timestamp before 1970 is refused in every column`() {
        TIMESTAMP_COLUMNS.forEach { (column, set) ->
            assertRefused(set(sampleDocument(), -1), "$column -1")
            assertRefused(set(sampleDocument(), -100_000_000_000_000_000), "$column -100000000000000000")
        }
    }

    @Test
    fun `a timestamp after 9999 is refused in every column`() {
        TIMESTAMP_COLUMNS.forEach { (column, set) ->
            assertRefused(set(sampleDocument(), BackupValidator.MAX_TIMESTAMP + 1), column)
        }
    }

    // ─── Sets ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a set numbered below one is refused on both set tables`() {
        assertRefused(
            sampleDocument().let { it.copy(workoutSet = it.workoutSet.map { set -> set.copy(setNumber = 0) }) },
            "workout_set.set_number 0",
        )
        assertRefused(
            sampleDocument().let { it.copy(templateSet = it.templateSet.map { set -> set.copy(setNumber = -1) }) },
            "template_set.set_number -1",
        )
    }

    @Test
    fun `an RPE outside 10 to 100 is refused, and the edges and no RPE are not`() {
        assertRefused(sampleDocument().withRpe(9), "workout_set.rpe 9")
        assertRefused(sampleDocument().withRpe(101), "workout_set.rpe 101")
        assertNull(BackupValidator.validate(sampleDocument().withRpe(10)))
        assertNull(BackupValidator.validate(sampleDocument().withRpe(100)))
        assertNull(BackupValidator.validate(sampleDocument().withRpe(null)))
    }

    /** What a bound is not: a set heavier than `SetValidator` admits is the readers' to degrade on. */
    @Test
    fun `a set value past SetValidator's bounds is not refused`() {
        val document = sampleDocument().let { document ->
            document.copy(
                workoutSet = document.workoutSet.map { it.copy(weight = Long.MAX_VALUE, reps = Int.MAX_VALUE) },
            )
        }

        assertNull(BackupValidator.validate(document))
    }

    private fun ExportDocument.withRpe(rpe: Int?): ExportDocument =
        copy(workoutSet = workoutSet.map { it.copy(rpe = rpe) })

    private fun assertRefused(
        document: ExportDocument,
        prefix: String,
    ) {
        val violation: ImportError.ConstraintViolation? = BackupValidator.validate(document)
        assertNotNull("expected $prefix to be refused", violation)
        val reason = violation!!.reason
        assertTrue("expected the message to start with $prefix, was: $reason", reason.startsWith(prefix))
        assertTrue(reason, reason.contains("outside the accepted range"))
    }

    private companion object {

        /**
         * Every table whose `id` is an autogenerated rowid, and how to add one more row to it,
         * numbered as asked and pointing only at rows the fixture holds — so the only thing that
         * can be wrong with the document is the number.
         */
        val ID_COLUMNS: Map<BackupTable, (ExportDocument, Long) -> ExportDocument> = mapOf(
            BackupTable.MUSCLE_GROUP to { d, id -> d.copy(muscleGroup = d.muscleGroup + MuscleGroupRow(id, "NECK")) },
            BackupTable.TAG to { d, id -> d.copy(tag = d.tag + TagRow(id, "range")) },
            BackupTable.EXERCISE to { d, id -> d.copy(exercise = d.exercise + ExerciseRow(id, "Range Row")) },
            BackupTable.WORKOUT_TEMPLATE to { d, id ->
                d.copy(workoutTemplate = d.workoutTemplate + WorkoutTemplateRow(id, "Range"))
            },
            BackupTable.TEMPLATE_EXERCISE to { d, id ->
                d.copy(templateExercise = d.templateExercise + TemplateExerciseRow(id, 1, 1, position = 9))
            },
            BackupTable.TEMPLATE_SET to { d, id -> d.copy(templateSet = d.templateSet + TemplateSetRow(id, 1, 9)) },
            BackupTable.WORKOUT_SESSION to { d, id ->
                d.copy(
                    workoutSession = d.workoutSession + WorkoutSessionRow(
                        id,
                        // Finished: the fixture already holds the one session in progress a
                        // document may carry.
                        isInProgress = false,
                        lastActiveAt = 0,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
                )
            },
            BackupTable.SESSION_EXERCISE to { d, id ->
                val row = SessionExerciseRow(
                    id,
                    sessionId = 1,
                    exerciseId = 1,
                    position = 9,
                    createdAt = 0,
                    updatedAt = 0,
                )
                d.copy(sessionExercise = d.sessionExercise + row)
            },
            BackupTable.WORKOUT_SET to { d, id ->
                val row = WorkoutSetRow(id, 1, setNumber = 9, completedAt = 0, createdAt = 0, updatedAt = 0)
                d.copy(workoutSet = d.workoutSet + row)
            },
            BackupTable.PERSONAL_RECORD to { d, id ->
                val row = PersonalRecordRow(id, exerciseId = 1, workoutSetId = 1, prType = "MAX_WEIGHT", achievedAt = 0)
                d.copy(personalRecord = d.personalRecord + row)
            },
            BackupTable.BODY_MEASUREMENT to { d, id ->
                d.copy(
                    bodyMeasurement = d.bodyMeasurement + BodyMeasurementRow(
                        id,
                        weight = 1,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
                )
            },
            BackupTable.COLLECTED_ITEM to { d, id ->
                d.copy(collectedItem = d.collectedItem + CollectedItemRow(id, "steel_shaker", "range", acquiredAt = 0))
            },
            BackupTable.PET to { d, id ->
                val egg = CollectedItemRow(id = 900, collectibleId = "pet_egg", source = "range", acquiredAt = 0)
                val row = PetRow(id, "iron_cub", egg.id, hatchedAt = 0, 0, 0, 0, 0, nickname = null, isActive = false)
                d.copy(collectedItem = d.collectedItem + egg, pet = d.pet + row)
            },
        )

        /** Every timestamp column, as `table.column` and a way to write one value into it. */
        val TIMESTAMP_COLUMNS: List<Pair<String, (ExportDocument, Long) -> ExportDocument>> = listOf(
            "workout_session.completed_at" to { d, v ->
                d.copy(
                    workoutSession = d.workoutSession.map { it.copy(completedAt = v) },
                )
            },
            "workout_session.last_active_at" to { d, v ->
                d.copy(
                    workoutSession = d.workoutSession.map { it.copy(lastActiveAt = v) },
                )
            },
            "workout_session.created_at" to { d, v ->
                d.copy(
                    workoutSession = d.workoutSession.map { it.copy(createdAt = v) },
                )
            },
            "workout_session.updated_at" to { d, v ->
                d.copy(
                    workoutSession = d.workoutSession.map { it.copy(updatedAt = v) },
                )
            },
            "session_exercise.created_at" to { d, v ->
                d.copy(
                    sessionExercise = d.sessionExercise.map { it.copy(createdAt = v) },
                )
            },
            "session_exercise.updated_at" to { d, v ->
                d.copy(
                    sessionExercise = d.sessionExercise.map { it.copy(updatedAt = v) },
                )
            },
            "workout_set.completed_at" to { d, v ->
                d.copy(
                    workoutSet = d.workoutSet.map { it.copy(completedAt = v) },
                )
            },
            "workout_set.created_at" to { d, v -> d.copy(workoutSet = d.workoutSet.map { it.copy(createdAt = v) }) },
            "workout_set.updated_at" to { d, v -> d.copy(workoutSet = d.workoutSet.map { it.copy(updatedAt = v) }) },
            "personal_record.achieved_at" to { d, v ->
                d.copy(
                    personalRecord = d.personalRecord.map { it.copy(achievedAt = v) },
                )
            },
            "body_measurement.created_at" to { d, v ->
                d.copy(
                    bodyMeasurement = d.bodyMeasurement.map { it.copy(createdAt = v) },
                )
            },
            "body_measurement.updated_at" to { d, v ->
                d.copy(
                    bodyMeasurement = d.bodyMeasurement.map { it.copy(updatedAt = v) },
                )
            },
            "user_progress.last_counted_at" to { d, v ->
                d.copy(
                    userProgress = d.userProgress.map { it.copy(lastCountedAt = v) },
                )
            },
            "user_progress.protection_consumed_at" to { d, v ->
                d.copy(userProgress = d.userProgress.map { it.copy(protectionConsumedAt = v) })
            },
            "achievement.unlocked_at" to { d, v ->
                d.copy(
                    achievement = d.achievement.map { it.copy(unlockedAt = v) },
                )
            },
            "quest_completion.period_start" to { d, v ->
                d.copy(
                    questCompletion = d.questCompletion.map { it.copy(periodStart = v) },
                )
            },
            "quest_completion.completed_at" to { d, v ->
                d.copy(
                    questCompletion = d.questCompletion.map { it.copy(completedAt = v) },
                )
            },
            "collected_item.acquired_at" to { d, v ->
                d.copy(
                    collectedItem = d.collectedItem.map { it.copy(acquiredAt = v) },
                )
            },
            "pet.hatched_at" to { d, v ->
                d.copy(
                    pet = d.pet.map { it.copy(hatchedAt = v) },
                )
            },
        )

        fun ExportDocument.withRowId(
            table: BackupTable,
            id: Long,
        ): ExportDocument = ID_COLUMNS.getValue(table)(this, id)
    }
}
