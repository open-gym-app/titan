package com.titan.core.domain.backup

import com.titan.core.domain.backup.model.AchievementRow
import com.titan.core.domain.backup.model.ExerciseRow
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.backup.model.MuscleGroupRow
import com.titan.core.domain.backup.model.QuestCompletionRow
import com.titan.core.domain.backup.model.TagRow
import com.titan.core.domain.backup.model.WorkoutSessionRow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules `BackupValidator` holds that are about a document's shape rather than a range or
 * a key: one session in progress, and text a unique index can compare the way this check does.
 *
 * Both were audit findings under #48 (#257). The SQLite half of the second one — that two such
 * names really do collide on insert — is `HostileBackupTest`'s, over a real database.
 */
class BackupValidatorShapeTest {

    @Test
    fun `the fixture holds exactly one session in progress`() {
        assertTrue(sampleDocument().workoutSession.count { it.isInProgress } == 1)
        assertNull(BackupValidator.validate(sampleDocument()))
    }

    @Test
    fun `no session in progress is fine`() {
        val document = sampleDocument().let { doc ->
            doc.copy(workoutSession = doc.workoutSession.map { it.copy(isInProgress = false) })
        }

        assertNull(BackupValidator.validate(document))
    }

    @Test
    fun `a second session in progress is refused`() {
        val document = sampleDocument().let { it.copy(workoutSession = it.workoutSession + secondLiveSession()) }

        val violation = assertViolation(document)

        assertTrue(violation.reason, violation.reason.contains("2 sessions in progress"))
        assertTrue(violation.reason, violation.reason.contains("ids 2 and 3"))
    }

    @Test
    fun `an unpaired high surrogate in a muscle group name is refused`() {
        val document = sampleDocument().copy(muscleGroup = listOf(MuscleGroupRow(id = 1, name = "CHEST\uD800")))

        val violation = assertViolation(withoutCatalogueLinks(document))

        assertTrue(violation.reason, violation.reason.contains("muscle_group.name of row 1"))
    }

    /** The pair the audit described: distinct here, one string once SQLite has encoded them. */
    @Test
    fun `two tag names that differ only after a lone surrogate are refused`() {
        val document = sampleDocument().copy(
            tag = listOf(TagRow(id = 1, name = "\uD800A"), TagRow(id = 2, name = "\uD800с")),
        )

        val violation = assertViolation(document)

        assertTrue(violation.reason, violation.reason.contains("tag.name of row 1"))
        assertTrue(violation.reason, violation.reason.contains("unpaired surrogate"))
    }

    @Test
    fun `an unpaired low surrogate is refused as well`() {
        val document = sampleDocument().copy(
            tag = listOf(TagRow(id = 1, name = "PUSH"), TagRow(id = 2, name = "fav\uDC00ourites")),
        )

        assertTrue(assertViolation(document).reason.contains("tag.name of row 2"))
    }

    @Test
    fun `every text key is checked, the nullable ones included`() {
        val sample = sampleDocument()
        val cases = mapOf(
            "exercise.name" to sample.withFirstExercise { it.copy(name = "Bench\uD83D") },
            "exercise.equipment_type" to sample.withFirstExercise { it.copy(equipmentType = "\uDE00") },
            "exercise.machine_brand" to sample.withFirstExercise { it.copy(machineBrand = "Hammer\uD800") },
            "achievement.achievement_id" to sample.copy(
                achievement = listOf(AchievementRow(achievementId = "first\uD800", unlockedAt = 1)),
            ),
            "quest_completion.quest_id" to sample.copy(
                questCompletion = listOf(QuestCompletionRow(questId = "\uDBFF", periodStart = 1, completedAt = 1)),
            ),
        )

        cases.forEach { (column, document) ->
            val violation = assertViolation(document)
            assertTrue("$column: ${violation.reason}", violation.reason.contains(column))
        }
    }

    /** A real pair is one character, and an emoji in a name is a name. */
    @Test
    fun `a surrogate pair is well formed`() {
        val document = sampleDocument().copy(
            tag = listOf(TagRow(id = 1, name = "PUSH 💪"), TagRow(id = 2, name = "🏋")),
        )

        assertNull(BackupValidator.validate(document))
    }

    /** Text no index covers cannot collide, so the rule does not reach it: a record is not refused for it. */
    @Test
    fun `a lone surrogate in a session name is not refused`() {
        val document = sampleDocument().let { doc ->
            doc.copy(workoutSession = doc.workoutSession.map { it.copy(name = "Push\uD800") })
        }

        assertNull(BackupValidator.validate(document))
    }

    private fun ExportDocument.withFirstExercise(edit: (ExerciseRow) -> ExerciseRow) =
        copy(exercise = exercise.mapIndexed { index, row -> if (index == 0) edit(row) else row })

    private fun secondLiveSession() =
        WorkoutSessionRow(id = 3, lastActiveAt = 5_000, createdAt = 5_000, updatedAt = 5_000)

    /** The fixture's muscle links name group 2, which a one-group catalogue does not hold. */
    private fun withoutCatalogueLinks(document: ExportDocument) =
        document.copy(exerciseMuscleGroup = document.exerciseMuscleGroup.filter { it.muscleGroupId == 1L })

    private fun assertViolation(document: ExportDocument): ImportError.ConstraintViolation {
        val violation = BackupValidator.validate(document)
        assertNotNull("expected the document to be refused", violation)
        return violation!!
    }
}
