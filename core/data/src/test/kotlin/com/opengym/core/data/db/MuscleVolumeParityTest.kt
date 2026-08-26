package com.opengym.core.data.db

import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.stats.MuscleVolumeCalculator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the claim both implementations document: `WorkoutSetDao.getVolumeByMuscleGroup`'s
 * `weighted_volume` and the domain's [MuscleVolumeCalculator] attribute identically.
 *
 * The two exist because they answer at different scales - the SQL aggregate sums a
 * multi-year range inside SQLite, the calculator attributes a window already in
 * memory - and nothing but a test stops them drifting once someone edits one of them.
 * The truncation cases matter most: both must halve *each set* before summing.
 *
 * Rows are mapped to domain models here rather than through a repository because the
 * repository implementations do not exist yet (#68). When they land, this test should
 * read through them instead of mapping by hand.
 */
class MuscleVolumeParityTest : RoomTestBase() {

    private companion object {
        /** 2024-10-07, a Monday, matching AnalyticsDaoTest's anchor. */
        const val DAY_1 = 20_003L
        const val RANGE_END = DAY_1 + 7

        const val MILLIS_PER_DAY = 86_400_000L

        fun kg(value: Long): Long = value * 1_000_000L

        fun at(epochDay: Long): Long = epochDay * MILLIS_PER_DAY + 12 * 3_600_000L
    }

    @Test
    fun `a primary and two secondary links attribute the same either way`() =
        runBlocking {
            val muscles = seedMuscleGroups("CHEST", "TRICEPS", "SHOULDERS")
            val bench = db.exerciseDao().insert(exercise(name = "Bench Press"))
            link(bench, muscles.getValue("CHEST"), isPrimary = true)
            link(bench, muscles.getValue("TRICEPS"), isPrimary = false)
            link(bench, muscles.getValue("SHOULDERS"), isPrimary = false)
            val sessionExerciseId = seedSessionExerciseFor(bench)
            logSet(sessionExerciseId, 1, at(DAY_1), reps = 10, weight = kg(100))
            logSet(sessionExerciseId, 2, at(DAY_1 + 1), reps = 8, weight = kg(90))

            val (sql, _) = assertParity(mapOf(sessionExerciseId to bench))

            // 1000 + 720 lifted; each secondary takes half of each set: 500 + 360.
            assertEquals(kg(1_720), sql.getValue("CHEST"))
            assertEquals(kg(860), sql.getValue("TRICEPS"))
            assertEquals(kg(860), sql.getValue("SHOULDERS"))
        }

    @Test
    fun `a deeply compound exercise agrees across all five of its links`() =
        runBlocking {
            // Mirrors the seed's Barbell Squat: one primary, four secondary.
            val muscles = seedMuscleGroups(
                "QUADRICEPS",
                "CALVES",
                "GLUTES",
                "HAMSTRINGS",
                "LOWER_BACK",
            )
            val squat = db.exerciseDao().insert(exercise(name = "Barbell Squat"))
            link(squat, muscles.getValue("QUADRICEPS"), isPrimary = true)
            listOf("CALVES", "GLUTES", "HAMSTRINGS", "LOWER_BACK").forEach {
                link(squat, muscles.getValue(it), isPrimary = false)
            }
            val sessionExerciseId = seedSessionExerciseFor(squat)
            logSet(sessionExerciseId, 1, at(DAY_1), reps = 5, weight = kg(140))

            val (sql, _) = assertParity(mapOf(sessionExerciseId to squat))

            // 700 kg lifted, attributed as 700 + 4 x 350 = 2100. Both paths overshoot
            // the real total by the same factor - that is the documented rule, not drift.
            assertEquals(kg(700), sql.getValue("QUADRICEPS"))
            assertEquals(kg(350), sql.getValue("GLUTES"))
            assertEquals(kg(2_100), sql.values.sum())
        }

    @Test
    fun `odd volumes truncate the same way in both implementations`() =
        runBlocking {
            // Three sets of 3 mg-reps: each secondary share truncates 1.5 to 1, so the
            // secondary lands on 3 and not on 4 (which halving the 9 mg total would give).
            val muscles = seedMuscleGroups("CHEST", "TRICEPS")
            val exerciseId = db.exerciseDao().insert(exercise())
            link(exerciseId, muscles.getValue("CHEST"), isPrimary = true)
            link(exerciseId, muscles.getValue("TRICEPS"), isPrimary = false)
            val sessionExerciseId = seedSessionExerciseFor(exerciseId)
            repeat(3) { logSet(sessionExerciseId, it + 1, at(DAY_1), reps = 3, weight = 1L) }

            val (sql, domain) = assertParity(mapOf(sessionExerciseId to exerciseId))

            assertEquals(9L, sql.getValue("CHEST"))
            assertEquals(3L, domain.getValue("TRICEPS"))
        }

    @Test
    fun `incomplete and loadless sets are handled the same way by both`() =
        runBlocking {
            val muscles = seedMuscleGroups("CHEST", "TRICEPS")
            val exerciseId = db.exerciseDao().insert(exercise())
            link(exerciseId, muscles.getValue("CHEST"), isPrimary = true)
            link(exerciseId, muscles.getValue("TRICEPS"), isPrimary = false)
            val sessionExerciseId = seedSessionExerciseFor(exerciseId)
            logSet(sessionExerciseId, 1, at(DAY_1), reps = 10, weight = kg(100))
            logSet(sessionExerciseId, 2, at(DAY_1), reps = 12, weight = null)
            logSet(sessionExerciseId, 3, at(DAY_1), reps = 10, weight = kg(100), isCompleted = false)

            val (sql, _) = assertParity(mapOf(sessionExerciseId to exerciseId))

            // The planned-but-unperformed set is excluded; the loadless one contributes 0
            // without dropping its muscle groups.
            assertEquals(kg(1_000), sql.getValue("CHEST"))
        }

    @Test
    fun `two exercises sharing a muscle group agree on the combined total`() =
        runBlocking {
            val muscles = seedMuscleGroups("CHEST", "TRICEPS", "SHOULDERS")
            val bench = db.exerciseDao().insert(exercise(name = "Bench Press"))
            link(bench, muscles.getValue("CHEST"), isPrimary = true)
            link(bench, muscles.getValue("TRICEPS"), isPrimary = false)
            val pushdown = db.exerciseDao().insert(exercise(name = "Triceps Pushdown"))
            link(pushdown, muscles.getValue("TRICEPS"), isPrimary = true)
            link(pushdown, muscles.getValue("SHOULDERS"), isPrimary = false)

            val benchRow = seedSessionExerciseFor(bench)
            val pushdownRow = seedSessionExerciseFor(pushdown)
            logSet(benchRow, 1, at(DAY_1), reps = 10, weight = kg(100))
            logSet(pushdownRow, 1, at(DAY_1 + 1), reps = 12, weight = kg(30))

            val (sql, _) = assertParity(mapOf(benchRow to bench, pushdownRow to pushdown))

            // 500 as bench's secondary + 360 of its own.
            assertEquals(kg(860), sql.getValue("TRICEPS"))
        }

    @Test
    fun `the same exercise logged twice in a session agrees across both rows`() =
        runBlocking {
            val muscles = seedMuscleGroups("CHEST", "TRICEPS")
            val bench = db.exerciseDao().insert(exercise(name = "Bench Press"))
            link(bench, muscles.getValue("CHEST"), isPrimary = true)
            link(bench, muscles.getValue("TRICEPS"), isPrimary = false)
            val first = seedSessionExerciseFor(bench)
            val second = seedSessionExerciseFor(bench)
            logSet(first, 1, at(DAY_1), reps = 10, weight = kg(100))
            logSet(second, 1, at(DAY_1 + 1), reps = 10, weight = kg(80))

            val (sql, _) = assertParity(mapOf(first to bench, second to bench))

            assertEquals(kg(1_800), sql.getValue("CHEST"))
        }

    /**
     * Runs both paths over the seeded range and asserts they agree muscle for muscle,
     * returning the two results (keyed by muscle group name) so a caller can pin the
     * absolute figures on top of the equality.
     *
     * [exerciseBySessionExercise] is the session-exercise-to-exercise mapping a
     * repository would supply; the calculator needs it to key links the way a
     * [WorkoutSet] is keyed.
     */
    private suspend fun assertParity(
        exerciseBySessionExercise: Map<Long, Long>,
    ): Pair<Map<String, Long>, Map<String, Long>> {
        val sql = db.workoutSetDao().getVolumeByMuscleGroup(DAY_1, RANGE_END).first()
            .associate { it.muscleGroupName to it.weightedVolume }

        val sets = exerciseBySessionExercise.keys.flatMap { sessionExerciseId ->
            db.workoutSetDao().getBySessionExercise(sessionExerciseId).first().map(::toDomain)
        }
        val links = exerciseBySessionExercise.mapValues { (_, exerciseId) ->
            db.exerciseDao().getMuscleGroupLinks(exerciseId).first().map { row ->
                ExerciseMuscleGroupLink(
                    muscleGroup = MuscleGroup(row.muscleGroupId, row.muscleGroupName, row.muscleGroupIcon),
                    isPrimary = row.isPrimary,
                )
            }
        }
        val domain = MuscleVolumeCalculator.volumeByMuscle(sets, links)
            .mapKeys { (group, _) -> group.name }

        assertEquals("muscle groups differ between the SQL and domain paths", sql.keys, domain.keys)
        sql.forEach { (name, weighted) ->
            assertEquals("$name disagrees between the SQL and domain paths", weighted, domain[name])
        }
        return sql to domain
    }

    private fun toDomain(entity: com.opengym.core.data.db.entity.WorkoutSetEntity) =
        WorkoutSet(
            id = entity.id,
            sessionExerciseId = entity.sessionExerciseId,
            setNumber = entity.setNumber,
            reps = entity.reps,
            weight = entity.weight,
            completedAt = entity.completedAt,
            isCompleted = entity.isCompleted,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )

    // --- fixture helpers --------------------------------------------------

    private suspend fun seedSessionExerciseFor(exerciseId: Long): Long {
        val sessionId = db.workoutSessionDao().insert(session())
        return db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
    }

    private suspend fun seedMuscleGroups(vararg names: String): Map<String, Long> {
        db.muscleGroupDao().insertAll(names.map { muscleGroup(it) })
        return db.muscleGroupDao().getAll().first().associate { it.name to it.id }
    }

    private suspend fun link(
        exerciseId: Long,
        muscleGroupId: Long,
        isPrimary: Boolean,
    ) = db.exerciseDao().addMuscleGroup(exerciseMuscleGroup(exerciseId, muscleGroupId, isPrimary))

    private suspend fun logSet(
        sessionExerciseId: Long,
        setNumber: Int,
        completedAt: Long,
        reps: Int? = null,
        weight: Long? = null,
        isCompleted: Boolean = true,
    ) = db.workoutSetDao().insert(
        workoutSet(
            sessionExerciseId = sessionExerciseId,
            setNumber = setNumber,
            isCompleted = isCompleted,
            completedAt = completedAt,
            reps = reps,
            weight = weight,
        ),
    )
}
