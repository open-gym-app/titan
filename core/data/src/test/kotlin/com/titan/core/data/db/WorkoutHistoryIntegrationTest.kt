package com.titan.core.data.db

import com.titan.core.data.db.WorkoutHistoryFixture.Companion.exerciseSlotOf
import com.titan.core.data.db.WorkoutHistoryFixture.Companion.repsFor
import com.titan.core.data.db.WorkoutHistoryFixture.Companion.sessionCreatedAt
import com.titan.core.data.db.WorkoutHistoryFixture.Companion.setCompletedAt
import com.titan.core.data.db.WorkoutHistoryFixture.Companion.weightFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-DAO integration: a realistic multi-session history is written through
 * `WorkoutSessionDao` -> `SessionExerciseDao` -> `WorkoutSetDao` -> `PersonalRecordDao`
 * and then read back through the same DAOs, so that a change to one of them that
 * only breaks in combination with another (a wrong join, a lost column, an
 * ordering that only shows up beyond a handful of rows) fails here.
 *
 * The per-DAO tests - `WorkoutFlowDaoTest`, `AnalyticsDaoTest` - stay on small
 * hand-written fixtures; this one is about the whole chain holding together at
 * a size where structure matters, including what a delete takes with it.
 */
class WorkoutHistoryIntegrationTest : RoomTestBase() {

    private companion object {
        val SPEC = HistorySpec(
            sessionCount = 50,
            exercisesPerSession = 5,
            setsPerExercise = 4,
            // No trailing in-progress session: every session here is a complete,
            // fully populated one, which keeps the counts below exact.
            withActiveSession = false,
        )
    }

    @Test
    fun `the seeded history round-trips through every core DAO`() =
        runBlocking {
            val history = seedHistory(SPEC)

            val sessions = db.workoutSessionDao().getAll().first()
            assertEquals(SPEC.sessionCount, sessions.size)
            // getAll is newest-created first, the fixture seeds oldest first.
            assertEquals(history.sessionIds.reversed(), sessions.map { it.id })

            history.sessionIds.forEachIndexed { sessionIndex, sessionId ->
                val session = db.workoutSessionDao().getById(sessionId).first()
                assertEquals(sessionCreatedAt(sessionIndex), session?.createdAt)
                assertSessionExercises(history, sessionIndex, sessionId)
            }

            assertEquals(SPEC.sessionExerciseCount, countRows("session_exercise"))
            assertEquals(SPEC.setCount, countRows("workout_set"))
            assertEquals(SPEC.sessionCount, countRows("personal_record"))
        }

    @Test
    fun `every seeded set is reachable through the per-exercise history query`() =
        runBlocking {
            val history = seedHistory(SPEC)

            val setsPerExercise = history.exerciseIds.associateWith { exerciseId ->
                db.workoutSetDao().getHistoryByExercise(exerciseId).first()
            }

            // The join reaches every set exactly once, and each set is filed under
            // the exercise it was actually performed on - not just the right total.
            assertEquals(SPEC.setCount, setsPerExercise.values.sumOf { it.size })
            assertEquals(
                emptyList<Long>(),
                setsPerExercise.filterValues { it.isEmpty() }.keys.toList(),
            )
            setsPerExercise.values.forEach { sets ->
                assertEquals(
                    "per-exercise history is newest-completed first",
                    sets.map { it.completedAt }.sortedDescending(),
                    sets.map { it.completedAt },
                )
            }
        }

    @Test
    fun `deleting one session takes its exercises, sets and records with it`() =
        runBlocking {
            val history = seedHistory(SPEC)
            val deletedSessionId = history.sessionIds.first()

            db.workoutSessionDao().delete(deletedSessionId)

            assertEquals(0, db.sessionExerciseDao().getBySession(deletedSessionId).first().size)
            assertEquals(SPEC.sessionCount - 1, countRows("workout_session"))
            // Exactly one session's worth of children went, and nothing else.
            assertEquals(SPEC.sessionExerciseCount - SPEC.exercisesPerSession, countRows("session_exercise"))
            assertEquals(SPEC.setCount - SPEC.exercisesPerSession * SPEC.setsPerExercise, countRows("workout_set"))
            // The record cascades transitively: session -> workout_set -> personal_record.
            assertEquals(SPEC.sessionCount - 1, countRows("personal_record"))

            // The surviving sessions are untouched, down to their set contents.
            history.sessionIds.drop(1).forEachIndexed { offset, sessionId ->
                assertSessionExercises(history, sessionIndex = offset + 1, sessionId = sessionId)
            }
        }

    @Test
    fun `deleting every session leaves no orphaned rows behind`() =
        runBlocking {
            val history = seedHistory(SPEC)

            history.sessionIds.forEach { db.workoutSessionDao().delete(it) }

            assertEquals(0, countRows("session_exercise"))
            assertEquals(0, countRows("workout_set"))
            assertEquals(0, countRows("personal_record"))
            // The catalogue is not history: exercises outlive the sessions that used
            // them (session_exercise -> exercise is RESTRICT, not CASCADE).
            assertEquals(SPEC.exerciseCount, countRows("exercise"))
            assertEquals(WorkoutHistoryFixture.MUSCLE_GROUPS.size, countRows("muscle_group"))
        }

    /**
     * Asserts the exercises of one session, and their sets, read back exactly as
     * the fixture wrote them - order, foreign keys and set values.
     */
    private suspend fun assertSessionExercises(
        history: SeededHistory,
        sessionIndex: Int,
        sessionId: Long,
    ) {
        val sessionExercises = db.sessionExerciseDao().getBySession(sessionId).first()
        assertEquals(SPEC.exercisesPerSession, sessionExercises.size)
        assertEquals(
            List(SPEC.exercisesPerSession) { it },
            sessionExercises.map { it.position },
        )

        sessionExercises.forEachIndexed { exerciseSlot, sessionExercise ->
            assertEquals(sessionId, sessionExercise.sessionId)
            assertEquals(
                history.exerciseIds[exerciseSlotOf(sessionIndex, exerciseSlot, SPEC.exerciseCount)],
                sessionExercise.exerciseId,
            )

            val sets = db.workoutSetDao().getBySessionExercise(sessionExercise.id).first()
            assertEquals(SPEC.setsPerExercise, sets.size)
            sets.forEachIndexed { setIndex, set ->
                val setNumber = setIndex + 1
                assertEquals(setNumber, set.setNumber)
                assertEquals(repsFor(setNumber), set.reps)
                assertEquals(weightFor(exerciseSlot, setNumber), set.weight)
                assertEquals(
                    setCompletedAt(SPEC, sessionIndex, exerciseSlot, setNumber),
                    set.completedAt,
                )
            }
        }
    }
}
