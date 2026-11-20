package com.titan.core.data.repository

import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.db.exercise
import com.titan.core.data.db.session
import com.titan.core.data.db.sessionExercise
import com.titan.core.data.db.workoutSet
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.domain.model.Discipline
import com.titan.core.domain.model.WorkoutActivity
import com.titan.core.domain.model.WorkoutSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HealthConnectSessionRepositoryImplTest : RoomTestBase() {

    private val repository by lazy { HealthConnectSessionRepositoryImpl(db.workoutSessionDao(), SessionMapper()) }

    private val sessionDao by lazy { db.workoutSessionDao() }

    /** A finished session with one exercise per discipline, each with a set [completed] or not. */
    private suspend fun finishedSession(
        createdAt: Long = 1_000L,
        vararg exercises: Pair<String?, Boolean>,
    ): Long {
        val sessionId = sessionDao.insert(
            session(
                isInProgress = false,
                createdAt = createdAt,
                lastActiveAt = createdAt + 10,
                completedAt = createdAt + 10,
            ),
        )
        exercises.forEachIndexed { position, (discipline, completed) ->
            val exerciseId = db.exerciseDao().insert(
                exercise(name = "Exercise $sessionId-$position", discipline = discipline),
            )
            val slot = db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, isCompleted = completed))
        }
        return sessionId
    }

    private fun imported(
        id: String,
        startedAt: Long = 5_000L,
        activity: WorkoutActivity = WorkoutActivity.RUNNING,
    ) = WorkoutSession(
        name = "Morning run",
        createdAt = startedAt,
        completedAt = startedAt + 1_800_000L,
        lastActiveAt = startedAt + 1_800_000L,
        isInProgress = false,
        updatedAt = startedAt + 1_800_000L,
        healthConnectId = id,
        healthConnectActivity = activity,
    )

    @Test
    fun `a finished session comes back with the disciplines of what was done in it`() =
        runBlocking {
            val id = finishedSession(1_000L, "POWERLIFTING" to true, "OLYMPIC_WEIGHTLIFTING" to true)

            val finished = repository.getFinished(id)!!

            assertEquals(listOf(Discipline.POWERLIFTING, Discipline.OLYMPIC_WEIGHTLIFTING), finished.disciplines)
            assertEquals(WorkoutActivity.WEIGHTLIFTING, finished.activity)
        }

    @Test
    fun `an exercise with no completed set is not part of what the session was`() =
        runBlocking {
            // Planned and skipped: the stretch at the end nobody did does not make this a mixed
            // session.
            val id = finishedSession(1_000L, "STRETCHING" to true, "CARDIO" to false)

            assertEquals(listOf(Discipline.STRETCHING), repository.getFinished(id)!!.disciplines)
        }

    @Test
    fun `an exercise with no discipline reads back as null`() =
        runBlocking {
            val id = finishedSession(1_000L, null to true)

            assertEquals(listOf<Discipline?>(null), repository.getFinished(id)!!.disciplines)
        }

    @Test
    fun `a session still in progress is not finished`() =
        runBlocking {
            val id = sessionDao.insert(session(isInProgress = true, createdAt = 1_000L))

            assertNull(repository.getFinished(id))
        }

    @Test
    fun `an imported session keeps the activity Health Connect gave it`() =
        runBlocking {
            repository.insertImported(listOf(imported("run-1", activity = WorkoutActivity.HIKING)))

            val stored = repository.getFinishedStartedBetween(0, Long.MAX_VALUE).single()

            assertEquals("run-1", stored.session.healthConnectId)
            assertEquals(WorkoutActivity.HIKING, stored.activity)
            assertEquals(emptyList<Discipline?>(), stored.disciplines)
        }

    @Test
    fun `the window is by start, half open, oldest first`() =
        runBlocking {
            val before = finishedSession(999L, "STRENGTH" to true)
            val first = finishedSession(1_000L, "STRENGTH" to true)
            val last = finishedSession(1_999L, "STRENGTH" to true)
            val after = finishedSession(2_000L, "STRENGTH" to true)
            sessionDao.insert(session(isInProgress = true, createdAt = 1_500L))

            val window = repository.getFinishedStartedBetween(1_000L, 2_000L).map { it.session.id }

            assertEquals(listOf(first, last), window)
            assertEquals(false, before in window || after in window)
        }

    @Test
    fun `importing a record that is already stored adds nothing`() =
        runBlocking {
            assertEquals(
                2,
                repository.insertImported(listOf(imported("run-1"), imported("ride-1", startedAt = 9_000L))),
            )

            // The same record again, beside a new one: only the new one is counted.
            val inserted = repository.insertImported(listOf(imported("run-1"), imported("swim-1", startedAt = 20_000L)))

            assertEquals(1, inserted)
            assertEquals(3, countRows("workout_session"))
        }

    @Test
    fun `a session logged here is refused as an import`() =
        runBlocking {
            val logged = imported("x").copy(healthConnectId = null)

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.insertImported(listOf(logged)) }
            }
            Unit
        }
}
