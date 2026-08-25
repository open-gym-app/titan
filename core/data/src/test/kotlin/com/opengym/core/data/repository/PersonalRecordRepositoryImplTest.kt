package com.opengym.core.data.repository

import com.opengym.core.data.db.RoomTestBase
import com.opengym.core.data.db.entity.PersonalRecordEntity
import com.opengym.core.data.db.exercise
import com.opengym.core.data.db.session
import com.opengym.core.data.db.sessionExercise
import com.opengym.core.data.db.workoutSet
import com.opengym.core.data.mapper.PersonalRecordMapper
import com.opengym.core.domain.model.PrType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PersonalRecordRepositoryImpl] against a real (Robolectric) SQLite.
 *
 * The behaviour under test is entirely in the query — which of an exercise's records is *the*
 * one — so a mocked DAO would assert nothing but that the class calls the method it obviously
 * calls. Records are written straight through the DAO rather than through the PR engine,
 * which does not exist yet (#30); the repository does not care where a row came from.
 */
class PersonalRecordRepositoryImplTest : RoomTestBase() {

    private val repository by lazy {
        PersonalRecordRepositoryImpl(
            personalRecordDao = db.personalRecordDao(),
            personalRecordMapper = PersonalRecordMapper(),
        )
    }

    /** A set to hang records off. Records cascade from one, so they need a real row. */
    private suspend fun seedSet(exerciseName: String): Pair<Long, Long> {
        val exerciseId = db.exerciseDao().insert(exercise(name = exerciseName))
        val sessionId = db.workoutSessionDao().insert(session())
        val sessionExerciseId = db.sessionExerciseDao().insert(
            sessionExercise(sessionId = sessionId, exerciseId = exerciseId, position = 0),
        )
        val setId = db.workoutSetDao().insert(workoutSet(sessionExerciseId = sessionExerciseId, setNumber = 1))
        return exerciseId to setId
    }

    private suspend fun record(
        exerciseId: Long,
        setId: Long,
        type: PrType,
        achievedAt: Long,
        weight: Long? = null,
        reps: Int? = null,
    ) = db.personalRecordDao().insert(
        PersonalRecordEntity(
            exerciseId = exerciseId,
            workoutSetId = setId,
            prType = type.name,
            weight = weight,
            reps = reps,
            achievedAt = achievedAt,
        ),
    )

    @Test
    fun `an empty table yields no records`() =
        runBlocking {
            assertTrue(repository.getHeadlineRecords().first().isEmpty())
        }

    @Test
    fun `the heaviest lift outranks every other kind of record`() =
        runBlocking {
            val (exerciseId, setId) = seedSet("Bench Press")
            // The reps record is the most recent, so a query that only ordered by date would
            // pick it. It is also the less interesting of the two on a row.
            record(exerciseId, setId, PrType.MAX_REPS, achievedAt = 200, reps = 12)
            record(exerciseId, setId, PrType.MAX_WEIGHT, achievedAt = 100, weight = 92_000_000)
            record(exerciseId, setId, PrType.ESTIMATED_1RM, achievedAt = 300, weight = 104_000_000)

            val headline = repository.getHeadlineRecords().first().getValue(exerciseId)

            assertEquals(PrType.MAX_WEIGHT, headline.type)
            assertEquals(92_000_000L, headline.weight)
        }

    @Test
    fun `an exercise with only rep records shows the most recent of them`() =
        runBlocking {
            val (exerciseId, setId) = seedSet("Pull-Up")
            record(exerciseId, setId, PrType.MAX_REPS, achievedAt = 100, reps = 12)
            record(exerciseId, setId, PrType.MAX_REPS, achievedAt = 500, reps = 14)

            val headline = repository.getHeadlineRecords().first().getValue(exerciseId)

            assertEquals(PrType.MAX_REPS, headline.type)
            assertEquals(14, headline.reps)
        }

    @Test
    fun `each exercise gets its own headline`() =
        runBlocking {
            val (bench, benchSet) = seedSet("Bench Press")
            val (pullUp, pullUpSet) = seedSet("Pull-Up")
            record(bench, benchSet, PrType.MAX_WEIGHT, achievedAt = 100, weight = 92_000_000)
            record(pullUp, pullUpSet, PrType.MAX_REPS, achievedAt = 100, reps = 14)

            val headlines = repository.getHeadlineRecords().first()

            assertEquals(setOf(bench, pullUp), headlines.keys)
            assertEquals(PrType.MAX_WEIGHT, headlines.getValue(bench).type)
            assertEquals(PrType.MAX_REPS, headlines.getValue(pullUp).type)
        }

    @Test
    fun `a record whose type this build cannot read is dropped rather than guessed at`() =
        runBlocking {
            val (exerciseId, setId) = seedSet("Bench Press")
            db.personalRecordDao().insert(
                PersonalRecordEntity(
                    exerciseId = exerciseId,
                    workoutSetId = setId,
                    prType = "MAX_AURA",
                    weight = 92_000_000,
                    achievedAt = 100,
                ),
            )

            // The type says which figure carries the value, so a fallback would put a weight
            // under the wrong heading. Showing nothing is the honest outcome.
            assertTrue(repository.getHeadlineRecords().first().isEmpty())
        }
}
