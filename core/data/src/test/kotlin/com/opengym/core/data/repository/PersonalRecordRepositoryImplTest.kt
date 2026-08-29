package com.opengym.core.data.repository

import com.opengym.core.data.db.RoomTestBase
import com.opengym.core.data.db.entity.PersonalRecordEntity
import com.opengym.core.data.db.exercise
import com.opengym.core.data.db.session
import com.opengym.core.data.db.sessionExercise
import com.opengym.core.data.db.workoutSet
import com.opengym.core.data.mapper.PersonalRecordMapper
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `count is every row, across exercises and types`() =
        runBlocking {
            val (bench, benchSet) = seedSet("Bench Press")
            val (pullUp, pullUpSet) = seedSet("Pull-Up")
            record(bench, benchSet, PrType.MAX_WEIGHT, achievedAt = 100, weight = 92_000_000)
            record(bench, benchSet, PrType.MAX_REPS, achievedAt = 100, reps = 8)
            record(pullUp, pullUpSet, PrType.MAX_REPS, achievedAt = 100, reps = 14)

            // Three records, two exercises, one session — the figure is per record, matching
            // the one `XpEvent.PrBroken` event each one emits.
            assertEquals(3, repository.count())
        }

    @Test
    fun `count includes a row this build's mapper cannot read`() =
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

            // A record broken on a newer version is still a record broken. Dropping it here
            // would make an achievement threshold move with the app version.
            assertEquals(1, repository.count())
        }

    @Test
    fun `count is zero on an empty table`() =
        runBlocking {
            assertEquals(0, repository.count())
        }

    @Test
    fun `insertIfBetter writes a row through the mapper when nothing is on file yet`() =
        runBlocking {
            val (exerciseId, setId) = seedSet("Bench Press")

            val written = repository.insertIfBetter(
                exerciseId = exerciseId,
                type = PrType.MAX_WEIGHT,
                record = PersonalRecord(
                    exerciseId = exerciseId,
                    workoutSetId = setId,
                    type = PrType.MAX_WEIGHT,
                    weight = 92_000_000,
                    achievedAt = 100,
                ),
            ) { current -> current == null }

            assertEquals(92_000_000L, written?.weight)
            assertEquals(1, db.personalRecordDao().getByExercise(exerciseId).first().size)
        }

    @Test
    fun `insertIfBetter writes nothing and returns null when isBetter rejects the current row`() =
        runBlocking {
            val (exerciseId, setId) = seedSet("Bench Press")
            record(exerciseId, setId, PrType.MAX_WEIGHT, achievedAt = 100, weight = 95_000_000)

            val written = repository.insertIfBetter(
                exerciseId = exerciseId,
                type = PrType.MAX_WEIGHT,
                record = PersonalRecord(
                    exerciseId = exerciseId,
                    workoutSetId = setId,
                    type = PrType.MAX_WEIGHT,
                    weight = 92_000_000,
                    achievedAt = 200,
                ),
            ) { current -> (current?.weight ?: 0L) < 92_000_000L }

            assertNull(written)
            assertEquals(1, db.personalRecordDao().getByExercise(exerciseId).first().size)
        }

    @Test
    fun `insertIfBetter hands isBetter the most recently achieved row of that type, not the largest`() =
        runBlocking {
            val (exerciseId, setId) = seedSet("Bench Press")
            record(exerciseId, setId, PrType.MAX_WEIGHT, achievedAt = 100, weight = 92_000_000)
            record(exerciseId, setId, PrType.MAX_WEIGHT, achievedAt = 200, weight = 95_000_000)

            var seenCurrent: PersonalRecord? = null
            repository.insertIfBetter(
                exerciseId = exerciseId,
                type = PrType.MAX_WEIGHT,
                record = PersonalRecord(
                    exerciseId = exerciseId,
                    workoutSetId = setId,
                    type = PrType.MAX_WEIGHT,
                    weight = 96_000_000,
                    achievedAt = 300,
                ),
            ) { current ->
                seenCurrent = current
                true
            }

            assertEquals(95_000_000L, seenCurrent?.weight)
        }

    @Test
    fun `insertIfBetter does not cross exercises or types`() =
        runBlocking {
            val (bench, benchSet) = seedSet("Bench Press")
            val (pullUp, pullUpSet) = seedSet("Pull-Up")
            record(bench, benchSet, PrType.MAX_WEIGHT, achievedAt = 100, weight = 92_000_000)
            record(pullUp, pullUpSet, PrType.MAX_WEIGHT, achievedAt = 100, weight = 1_000_000)

            var seenForBenchReps: PersonalRecord? = null
            repository.insertIfBetter(
                exerciseId = bench,
                type = PrType.MAX_REPS,
                record = PersonalRecord(
                    exerciseId = bench,
                    workoutSetId = benchSet,
                    type = PrType.MAX_REPS,
                    reps = 12,
                    achievedAt = 200,
                ),
            ) { current ->
                seenForBenchReps = current
                true
            }
            assertNull(seenForBenchReps)

            var seenForPullUpWeight: PersonalRecord? = null
            repository.insertIfBetter(
                exerciseId = pullUp,
                type = PrType.MAX_WEIGHT,
                record = PersonalRecord(
                    exerciseId = pullUp,
                    workoutSetId = pullUpSet,
                    type = PrType.MAX_WEIGHT,
                    weight = 2_000_000,
                    achievedAt = 200,
                ),
            ) { current ->
                seenForPullUpWeight = current
                true
            }
            assertEquals(1_000_000L, seenForPullUpWeight?.weight)
        }
}
