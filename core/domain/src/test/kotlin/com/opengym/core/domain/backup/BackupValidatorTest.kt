package com.opengym.core.domain.backup

import com.opengym.core.domain.backup.model.ExerciseMuscleGroupRow
import com.opengym.core.domain.backup.model.ExerciseRow
import com.opengym.core.domain.backup.model.ExerciseTagRow
import com.opengym.core.domain.backup.model.ExportDocument
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.backup.model.MuscleGroupRow
import com.opengym.core.domain.backup.model.PersonalRecordRow
import com.opengym.core.domain.backup.model.SessionExerciseRow
import com.opengym.core.domain.backup.model.SessionLootClaimRow
import com.opengym.core.domain.backup.model.SessionXpClaimRow
import com.opengym.core.domain.backup.model.TagRow
import com.opengym.core.domain.backup.model.TemplateExerciseRow
import com.opengym.core.domain.backup.model.TemplateSetRow
import com.opengym.core.domain.backup.model.UserProgressRow
import com.opengym.core.domain.backup.model.WorkoutSessionRow
import com.opengym.core.domain.backup.model.WorkoutSetRow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every foreign key in the schema, broken one at a time.
 *
 * Sixteen edges and one `CHECK`, each with a test that takes the valid fixture and breaks
 * exactly that one thing — so a failure names the edge rather than "the document is invalid".
 * The uniqueness half lives in [BackupValidatorUniquenessTest].
 */
class BackupValidatorTest {

    @Test
    fun `the fixture describes a database SQLite would accept`() {
        assertNull(BackupValidator.validate(sampleDocument()))
    }

    /**
     * The acceptance criterion's own case, verbatim: a `workout_set` pointing at a
     * `session_exercise` that is not in the file.
     */
    @Test
    fun `a set naming a session exercise the document does not hold is refused`() {
        val document = sampleDocument().let { it.copy(workoutSet = it.workoutSet + orphanSet()) }

        val violation = assertViolation(document)

        assertTrue(violation.reason, violation.reason.contains("workout_set.session_exercise_id 999"))
        assertTrue(violation.reason, violation.reason.contains("session_exercise"))
    }

    @Test
    fun `an exercise muscle link naming a missing exercise is refused`() {
        val document = sampleDocument().let {
            it.copy(
                exerciseMuscleGroup = it.exerciseMuscleGroup + ExerciseMuscleGroupRow(
                    exerciseId = 99,
                    muscleGroupId = 1,
                ),
            )
        }

        assertNamesEdge(document, "exercise_muscle_group.exercise_id")
    }

    @Test
    fun `an exercise muscle link naming a missing muscle group is refused`() {
        val document = sampleDocument().let {
            it.copy(
                exerciseMuscleGroup = it.exerciseMuscleGroup + ExerciseMuscleGroupRow(
                    exerciseId = 1,
                    muscleGroupId = 99,
                ),
            )
        }

        assertNamesEdge(document, "exercise_muscle_group.muscle_group_id")
    }

    @Test
    fun `an exercise tag naming a missing exercise is refused`() {
        val document = sampleDocument().let {
            it.copy(exerciseTag = it.exerciseTag + ExerciseTagRow(exerciseId = 99, tagId = 1))
        }

        assertNamesEdge(document, "exercise_tag.exercise_id")
    }

    @Test
    fun `an exercise tag naming a missing tag is refused`() {
        val document = sampleDocument().let {
            it.copy(exerciseTag = it.exerciseTag + ExerciseTagRow(exerciseId = 2, tagId = 99))
        }

        assertNamesEdge(document, "exercise_tag.tag_id")
    }

    @Test
    fun `a template exercise naming a missing template is refused`() {
        val document = sampleDocument().let {
            it.copy(
                templateExercise = it.templateExercise + TemplateExerciseRow(
                    id = 9,
                    templateId = 99,
                    exerciseId = 1,
                    position = 9,
                ),
            )
        }

        assertNamesEdge(document, "template_exercise.template_id")
    }

    @Test
    fun `a template exercise naming a missing exercise is refused`() {
        val document = sampleDocument().let {
            it.copy(
                templateExercise = it.templateExercise + TemplateExerciseRow(
                    id = 9,
                    templateId = 1,
                    exerciseId = 99,
                    position = 9,
                ),
            )
        }

        assertNamesEdge(document, "template_exercise.exercise_id")
    }

    @Test
    fun `a template set naming a missing template exercise is refused`() {
        val document = sampleDocument().let {
            it.copy(templateSet = it.templateSet + TemplateSetRow(id = 9, templateExerciseId = 99, setNumber = 1))
        }

        assertNamesEdge(document, "template_set.template_exercise_id")
    }

    @Test
    fun `a session naming a missing template is refused`() {
        val document = sampleDocument().let {
            it.copy(
                workoutSession = it.workoutSession +
                    WorkoutSessionRow(id = 9, templateId = 99, lastActiveAt = 0, createdAt = 0, updatedAt = 0),
            )
        }

        assertNamesEdge(document, "workout_session.template_id")
    }

    /** `SET NULL`, so a null is the row saying it came from no template — never a violation. */
    @Test
    fun `a session naming no template at all is fine`() {
        val document = sampleDocument().let {
            it.copy(
                workoutSession = it.workoutSession +
                    WorkoutSessionRow(id = 9, templateId = null, lastActiveAt = 0, createdAt = 0, updatedAt = 0),
            )
        }

        assertNull(BackupValidator.validate(document))
    }

    @Test
    fun `a session exercise naming a missing session is refused`() {
        val document = sampleDocument().let {
            it.copy(
                sessionExercise = it.sessionExercise +
                    SessionExerciseRow(
                        id = 9,
                        sessionId = 99,
                        exerciseId = 1,
                        position = 9,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
            )
        }

        assertNamesEdge(document, "session_exercise.session_id")
    }

    @Test
    fun `a session exercise naming a missing exercise is refused`() {
        val document = sampleDocument().let {
            it.copy(
                sessionExercise = it.sessionExercise +
                    SessionExerciseRow(
                        id = 9,
                        sessionId = 1,
                        exerciseId = 99,
                        position = 9,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
            )
        }

        assertNamesEdge(document, "session_exercise.exercise_id")
    }

    @Test
    fun `a session exercise naming a missing template exercise is refused`() {
        val document = sampleDocument().let {
            it.copy(
                sessionExercise = it.sessionExercise + SessionExerciseRow(
                    id = 9,
                    sessionId = 1,
                    exerciseId = 1,
                    position = 9,
                    templateExerciseId = 99,
                    createdAt = 0,
                    updatedAt = 0,
                ),
            )
        }

        assertNamesEdge(document, "session_exercise.template_exercise_id")
    }

    @Test
    fun `a personal record naming a missing exercise is refused`() {
        val document = sampleDocument().let {
            it.copy(
                personalRecord = it.personalRecord +
                    PersonalRecordRow(id = 9, exerciseId = 99, workoutSetId = 1, prType = "MAX_WEIGHT", achievedAt = 0),
            )
        }

        assertNamesEdge(document, "personal_record.exercise_id")
    }

    @Test
    fun `a personal record naming a missing set is refused`() {
        val document = sampleDocument().let {
            it.copy(
                personalRecord = it.personalRecord +
                    PersonalRecordRow(id = 9, exerciseId = 1, workoutSetId = 99, prType = "MAX_WEIGHT", achievedAt = 0),
            )
        }

        assertNamesEdge(document, "personal_record.workout_set_id")
    }

    @Test
    fun `an XP claim naming a missing session is refused`() {
        val document = sampleDocument().let {
            it.copy(sessionXpClaim = it.sessionXpClaim + SessionXpClaimRow(sessionId = 99))
        }

        assertNamesEdge(document, "session_xp_claim.session_id")
    }

    @Test
    fun `a loot claim naming a missing session is refused`() {
        val document = sampleDocument().let {
            it.copy(sessionLootClaim = it.sessionLootClaim + SessionLootClaimRow(sessionId = 99))
        }

        assertNamesEdge(document, "session_loot_claim.session_id")
    }

    @Test
    fun `a second user progress row is refused`() {
        val document = sampleDocument().let {
            it.copy(userProgress = it.userProgress + it.userProgress.first().copy(totalXp = 9))
        }

        val violation = assertViolation(document)

        assertTrue(violation.reason, violation.reason.contains("user_progress holds 2 rows"))
    }

    @Test
    fun `a user progress row numbered anything but one is refused`() {
        val document = sampleDocument().copy(
            userProgress = listOf(
                UserProgressRow(
                    id = 2,
                    totalXp = 0,
                    currentLevel = 1,
                    currentStreak = 0,
                    longestStreak = 0,
                    protectionRemaining = 3,
                ),
            ),
        )

        val violation = assertViolation(document)

        assertTrue(violation.reason, violation.reason.contains("user_progress.id must be 1"))
    }

    /** No row at all is the state before the first XP award, and is not a violation. */
    @Test
    fun `no user progress row is fine`() {
        assertNull(BackupValidator.validate(sampleDocument().copy(userProgress = emptyList())))
    }

    /** An empty document has nothing to break: a fresh install exports one, and it must restore. */
    @Test
    fun `an empty document is valid`() {
        val empty = ExportDocument(exportedAt = 0, appVersion = "x", preferences = samplePreferences())

        assertNull(BackupValidator.validate(empty))
    }

    private fun assertNamesEdge(
        document: ExportDocument,
        edge: String,
    ) {
        val violation = assertViolation(document)
        assertTrue("expected the message to name $edge, was: ${violation.reason}", violation.reason.startsWith(edge))
    }

    private fun assertViolation(document: ExportDocument): ImportError.ConstraintViolation {
        val violation = BackupValidator.validate(document)
        assertNotNull("expected the document to be refused", violation)
        return violation!!
    }

    private fun orphanSet() =
        WorkoutSetRow(
            id = 99,
            sessionExerciseId = 999,
            setNumber = 1,
            completedAt = 0,
            createdAt = 0,
            updatedAt = 0,
        )
}

/**
 * The other half: every primary key and unique index the schema declares, broken one at a time.
 *
 * Split from [BackupValidatorTest] because seventeen referential tests and ten uniqueness ones
 * in one class is a class nobody reads the shape of — and because the two ask different
 * questions. A dangling reference is a *truncated* file; a duplicate key is a *merged* one.
 */
class BackupValidatorUniquenessTest {

    @Test
    fun `two muscle groups with one id are refused`() {
        val document = sampleDocument().let {
            it.copy(
                muscleGroup = it.muscleGroup + MuscleGroupRow(id = 1, name = "BACK"),
            )
        }

        assertNamesClash(document, "muscle_group holds two rows with the same id")
    }

    @Test
    fun `two muscle groups with one name are refused`() {
        val document = sampleDocument().let {
            it.copy(
                muscleGroup = it.muscleGroup + MuscleGroupRow(id = 9, name = "CHEST"),
            )
        }

        assertNamesClash(document, "muscle_group holds two rows with the same name")
    }

    @Test
    fun `two tags with one name are refused`() {
        val document = sampleDocument().let { it.copy(tag = it.tag + TagRow(id = 9, name = "PUSH")) }

        assertNamesClash(document, "tag holds two rows with the same name")
    }

    @Test
    fun `two exercises with one id are refused`() {
        val document = sampleDocument().let { it.copy(exercise = it.exercise + ExerciseRow(id = 1, name = "Squat")) }

        assertNamesClash(document, "exercise holds two rows with the same id")
    }

    /**
     * The natural key collides only when every component is present — SQLite treats NULLs as
     * distinct in a unique index, which is what keeps two brandless rows of one name apart.
     */
    @Test
    fun `two exercises with one name, equipment and brand are refused`() {
        val branded = ExerciseRow(id = 9, name = "Chest Press", equipmentType = "MACHINE", machineBrand = "Hammer")
        val document = sampleDocument().let { it.copy(exercise = it.exercise + branded + branded.copy(id = 10)) }

        assertNamesClash(document, "exercise holds two rows with the same (name, equipment_type, machine_brand)")
    }

    @Test
    fun `two exercises with one name and no brand are not a clash`() {
        val document = sampleDocument().let {
            it.copy(
                exercise = it.exercise +
                    ExerciseRow(id = 9, name = "Row", equipmentType = "BARBELL", machineBrand = null) +
                    ExerciseRow(id = 10, name = "Row", equipmentType = "BARBELL", machineBrand = null),
            )
        }

        assertNull(BackupValidator.validate(document))
    }

    @Test
    fun `a repeated exercise muscle link is refused`() {
        val document = sampleDocument().let {
            it.copy(
                exerciseMuscleGroup = it.exerciseMuscleGroup + ExerciseMuscleGroupRow(
                    exerciseId = 1,
                    muscleGroupId = 1,
                ),
            )
        }

        assertNamesClash(document, "exercise_muscle_group holds two rows with the same id pair")
    }

    @Test
    fun `a repeated exercise tag is refused`() {
        val document = sampleDocument().let {
            it.copy(
                exerciseTag = it.exerciseTag + ExerciseTagRow(exerciseId = 1, tagId = 1),
            )
        }

        assertNamesClash(document, "exercise_tag holds two rows with the same id pair")
    }

    @Test
    fun `two template exercises on one position are refused`() {
        val document = sampleDocument().let {
            it.copy(
                templateExercise = it.templateExercise + TemplateExerciseRow(
                    id = 9,
                    templateId = 1,
                    exerciseId = 2,
                    position = 0,
                ),
            )
        }

        assertNamesClash(document, "template_exercise holds two rows with the same (template_id, position)")
    }

    @Test
    fun `two template sets on one set number are refused`() {
        val document = sampleDocument().let {
            it.copy(templateSet = it.templateSet + TemplateSetRow(id = 9, templateExerciseId = 1, setNumber = 1))
        }

        assertNamesClash(document, "template_set holds two rows with the same (template_exercise_id, set_number)")
    }

    @Test
    fun `two session exercises on one position are refused`() {
        val document = sampleDocument().let {
            it.copy(
                sessionExercise = it.sessionExercise +
                    SessionExerciseRow(
                        id = 9,
                        sessionId = 1,
                        exerciseId = 1,
                        position = 0,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
            )
        }

        assertNamesClash(document, "session_exercise holds two rows with the same (session_id, position)")
    }

    /**
     * `workout_set` has no unique index on `(session_exercise_id, set_number)` — the denseness
     * is the use cases' invariant, not the schema's — so two sets numbered 1 under one exercise
     * are a document the database accepts, and the importer must accept it too.
     */
    @Test
    fun `two sets with one set number under one exercise are not a clash`() {
        val document = sampleDocument().let {
            it.copy(
                workoutSet = it.workoutSet +
                    WorkoutSetRow(
                        id = 9,
                        sessionExerciseId = 1,
                        setNumber = 1,
                        completedAt = 0,
                        createdAt = 0,
                        updatedAt = 0,
                    ),
            )
        }

        assertNull(BackupValidator.validate(document))
    }

    @Test
    fun `two claims for one session are refused`() {
        val document = sampleDocument().let {
            it.copy(
                sessionXpClaim = it.sessionXpClaim + SessionXpClaimRow(sessionId = 1),
            )
        }

        assertNamesClash(document, "session_xp_claim holds two rows with the same session_id")
    }

    /** The inventory is a bag: two rows naming one collectible are two things the user holds. */
    @Test
    fun `two collected items naming one collectible are not a clash`() {
        assertNull(BackupValidator.validate(sampleDocument()))
    }

    private fun assertNamesClash(
        document: ExportDocument,
        expected: String,
    ) {
        val violation = BackupValidator.validate(document)
        assertNotNull("expected the document to be refused", violation)
        // `startsWith` rather than an equality: the message ends with the offending key, and a
        // composite one is a `Pair`/`Triple` whose `toString` is not worth pinning here.
        assertTrue("expected \"$expected…\", was: ${violation!!.reason}", violation.reason.startsWith(expected))
    }
}
