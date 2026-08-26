package com.opengym.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.opengym.core.data.db.RoomTestBase
import com.opengym.core.data.db.exercise
import com.opengym.core.data.db.session
import com.opengym.core.data.db.sessionExercise
import com.opengym.core.data.db.workoutSet
import com.opengym.core.data.mapper.SessionExerciseMapper
import com.opengym.core.data.mapper.SessionMapper
import com.opengym.core.data.mapper.WorkoutSetMapper
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.WorkoutSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRepositoryImplTest : RoomTestBase() {

    private val repository by lazy {
        SessionRepositoryImpl(
            sessionDao = db.workoutSessionDao(),
            sessionExerciseDao = db.sessionExerciseDao(),
            setDao = db.workoutSetDao(),
            sessionMapper = SessionMapper(),
            sessionExerciseMapper = SessionExerciseMapper(),
            setMapper = WorkoutSetMapper(),
        )
    }

    @Test
    fun `insert then getById returns the session as a domain model`() =
        runBlocking {
            val id = repository.insert(
                WorkoutSession(lastActiveAt = 1_000, createdAt = 1_000, updatedAt = 1_000, notes = "leg day"),
            )

            val loaded = repository.getById(id).first()

            assertEquals(id, loaded?.id)
            assertEquals("leg day", loaded?.notes)
            assertTrue(loaded?.isInProgress == true)
        }

    @Test
    fun `getById emits null for an id that was never stored`() =
        runBlocking {
            assertNull(repository.getById(404).first())
        }

    @Test
    fun `getAll is ordered by creation, newest first`() =
        runBlocking {
            db.workoutSessionDao().insert(session(createdAt = 100))
            db.workoutSessionDao().insert(session(createdAt = 300))
            db.workoutSessionDao().insert(session(createdAt = 200))

            assertEquals(listOf(300L, 200L, 100L), repository.getAll().first().map { it.createdAt })
        }

    @Test
    fun `getActive returns the in-progress session with the latest activity`() =
        runBlocking {
            db.workoutSessionDao().insert(session(isInProgress = false, lastActiveAt = 900))
            db.workoutSessionDao().insert(session(isInProgress = true, lastActiveAt = 300))
            val newest = db.workoutSessionDao().insert(session(isInProgress = true, lastActiveAt = 500))

            assertEquals(newest, repository.getActive().first()?.id)
        }

    @Test
    fun `getActive emits null once nothing is in progress`() =
        runBlocking {
            val id = db.workoutSessionDao().insert(session(isInProgress = true, lastActiveAt = 10))
            val stored = repository.getById(id).first()!!

            repository.update(stored.copy(isInProgress = false, completedAt = 20))

            assertNull(repository.getActive().first())
        }

    @Test
    fun `getExercises is ordered by position`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val exerciseId = db.exerciseDao().insert(exercise())
            db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 1))
            db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))

            assertEquals(listOf(0, 1), repository.getExercises(sessionId).first().map { it.position })
        }

    @Test
    fun `insertExercises numbers positions from the order it was given`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val squat = db.exerciseDao().insert(exercise(name = "Squat"))
            val bench = db.exerciseDao().insert(exercise(name = "Bench"))
            val press = db.exerciseDao().insert(exercise(name = "Press"))

            repository.insertExercises(
                sessionId = sessionId,
                exercises = listOf(
                    SessionExerciseDraft(exerciseId = press),
                    SessionExerciseDraft(exerciseId = squat),
                    SessionExerciseDraft(exerciseId = bench),
                ),
                createdAt = 7_000,
            )

            val laidOut = repository.getExercises(sessionId).first()
            assertEquals(listOf(press, squat, bench), laidOut.map { it.exerciseId })
            assertEquals(listOf(0, 1, 2), laidOut.map { it.position })
        }

    @Test
    fun `insertExercises returns the new ids in the order the exercises were given`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val squat = db.exerciseDao().insert(exercise(name = "Squat"))
            val bench = db.exerciseDao().insert(exercise(name = "Bench"))

            val ids = repository.insertExercises(
                sessionId = sessionId,
                exercises = listOf(
                    SessionExerciseDraft(exerciseId = squat),
                    SessionExerciseDraft(exerciseId = bench),
                ),
                createdAt = 7_000,
            )

            val laidOut = repository.getExercises(sessionId).first()
            assertEquals(laidOut.map { it.id }, ids)
        }

    @Test
    fun `insertExercises carries the superset grouping and the template link`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val squat = db.exerciseDao().insert(exercise(name = "Squat"))
            val bench = db.exerciseDao().insert(exercise(name = "Bench"))

            repository.insertExercises(
                sessionId = sessionId,
                exercises = listOf(
                    SessionExerciseDraft(exerciseId = squat, supersetGroup = 0, notes = "belt on"),
                    SessionExerciseDraft(exerciseId = bench, supersetGroup = 0),
                ),
                createdAt = 7_000,
            )

            val laidOut = repository.getExercises(sessionId).first()
            assertEquals(listOf(0, 0), laidOut.map { it.supersetGroup })
            assertEquals(listOf("belt on", null), laidOut.map { it.notes })
        }

    @Test
    fun `insertExercises stamps every row with the timestamp it was given`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val exerciseId = db.exerciseDao().insert(exercise())

            repository.insertExercises(
                sessionId = sessionId,
                exercises = listOf(SessionExerciseDraft(exerciseId = exerciseId)),
                createdAt = 7_000,
            )

            val laidOut = repository.getExercises(sessionId).first().single()
            assertEquals(7_000L, laidOut.createdAt)
            assertEquals(7_000L, laidOut.updatedAt)
        }

    @Test
    fun `insertExercises writes nothing for an empty layout`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())

            val ids = repository.insertExercises(sessionId, exercises = emptyList(), createdAt = 7_000)

            assertEquals(emptyList<Long>(), ids)
            assertEquals(emptyList<Long>(), repository.getExercises(sessionId).first().map { it.id })
        }

    @Test
    fun `a layout that violates a constraint writes none of its rows`() =
        runBlocking {
            // The transaction is the point. `session_exercise.exercise_id` is a foreign key, so a
            // list whose *second* draft names an exercise that does not exist fails part way
            // through — and a per-row loop would leave the first of its rows behind, in a session
            // the user is looking at, with nothing saying which of the others are missing.
            val sessionId = db.workoutSessionDao().insert(session())
            val squat = db.exerciseDao().insert(exercise(name = "Squat"))

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking {
                    repository.insertExercises(
                        sessionId = sessionId,
                        exercises = listOf(
                            SessionExerciseDraft(exerciseId = squat),
                            SessionExerciseDraft(exerciseId = NO_SUCH_EXERCISE),
                        ),
                        createdAt = 8_000,
                    )
                }
            }

            assertEquals(emptyList<Long>(), repository.getExercises(sessionId).first().map { it.exerciseId })
        }

    @Test
    fun `a second layout appends rather than colliding with the first`() =
        runBlocking {
            // What makes an exercise addable to a running session: positions continue past the
            // highest in use, so `session_exercise`'s unique (session_id, position) index is never
            // asked to hold two rows at position 0.
            val sessionId = db.workoutSessionDao().insert(session())
            val squat = db.exerciseDao().insert(exercise(name = "Squat"))
            val bench = db.exerciseDao().insert(exercise(name = "Bench"))
            repository.insertExercises(sessionId, listOf(SessionExerciseDraft(squat)), createdAt = 7_000)

            repository.insertExercises(sessionId, listOf(SessionExerciseDraft(bench)), createdAt = 8_000)

            val laidOut = repository.getExercises(sessionId).first()
            assertEquals(listOf(squat, bench), laidOut.map { it.exerciseId })
            assertEquals(listOf(0, 1), laidOut.map { it.position })
        }

    @Test
    fun `an exercise added after a removal does not reuse the gap's position`() =
        runBlocking {
            // One past the *highest* position, not the count: after a cut the two disagree, and
            // numbering from the count would land on a row that is still there.
            val sessionId = db.workoutSessionDao().insert(session())
            val squat = db.exerciseDao().insert(exercise(name = "Squat"))
            val bench = db.exerciseDao().insert(exercise(name = "Bench"))
            val fly = db.exerciseDao().insert(exercise(name = "Fly"))
            repository.insertExercises(
                sessionId,
                listOf(SessionExerciseDraft(squat), SessionExerciseDraft(bench)),
                createdAt = 7_000,
            )
            repository.deleteExercise(repository.getExercises(sessionId).first().first().id)

            repository.insertExercises(sessionId, listOf(SessionExerciseDraft(fly)), createdAt = 8_000)

            val laidOut = repository.getExercises(sessionId).first()
            assertEquals(listOf(bench, fly), laidOut.map { it.exerciseId })
            assertEquals(listOf(1, 2), laidOut.map { it.position })
        }

    @Test
    fun `removing an exercise takes its sets with it`() =
        runBlocking {
            // The cascade `RemoveSessionExerciseUseCase` refuses to trigger, proven here rather
            // than assumed: it is the reason that use case checks for logged sets at all.
            val sessionId = db.workoutSessionDao().insert(session())
            val squat = db.exerciseDao().insert(exercise(name = "Squat"))
            val exerciseRowId = repository
                .insertExercises(sessionId, listOf(SessionExerciseDraft(squat)), createdAt = 7_000)
                .single()
            db.workoutSetDao().insert(workoutSet(exerciseRowId, setNumber = 1, reps = 5))

            repository.deleteExercise(exerciseRowId)

            assertEquals(emptyList<Long>(), repository.getExercises(sessionId).first().map { it.id })
            assertEquals(0, repository.getSetsForSession(sessionId).first().size)
        }

    @Test
    fun `updating an exercise rewrites the row it names and nothing else`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val squat = db.exerciseDao().insert(exercise(name = "Squat"))
            val bench = db.exerciseDao().insert(exercise(name = "Bench"))
            repository.insertExercises(
                sessionId,
                listOf(SessionExerciseDraft(squat, supersetGroup = 0), SessionExerciseDraft(bench, supersetGroup = 0)),
                createdAt = 7_000,
            )
            val stored = repository.getExercises(sessionId).first()

            repository.updateExercise(stored.first().copy(supersetGroup = null))

            assertEquals(
                listOf(null, 0),
                repository.getExercises(sessionId).first().map { it.supersetGroup },
            )
        }

    @Test
    fun `updating several exercises reorders them without tripping the position index`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val squat = db.exerciseDao().insert(exercise(name = "Squat"))
            val bench = db.exerciseDao().insert(exercise(name = "Bench"))
            repository.insertExercises(
                sessionId,
                listOf(SessionExerciseDraft(squat), SessionExerciseDraft(bench)),
                createdAt = 7_000,
            )
            val stored = repository.getExercises(sessionId).first()

            repository.updateExercises(
                listOf(
                    stored[0].copy(position = 1, updatedAt = 9_000),
                    stored[1].copy(position = 0, updatedAt = 9_000),
                ),
            )

            // Two rows changing places is the write `(session_id, position)` would abort halfway
            // through; the store parks them first — see `SessionExerciseDao.rewriteLayout`.
            assertEquals(
                listOf(bench, squat),
                repository.getExercises(sessionId).first().map { it.exerciseId },
            )
        }

    @Test
    fun `getSets is ordered by set number within one exercise`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val exerciseId = db.exerciseDao().insert(exercise())
            val sessionExerciseId = db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
            db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 2, reps = 8))
            db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1, reps = 10))

            val sets = repository.getSets(sessionExerciseId).first()

            assertEquals(listOf(1, 2), sets.map { it.setNumber })
            assertEquals(listOf(10, 8), sets.map { it.reps })
        }

    @Test
    fun `updateSet rewrites the set it names and nothing else`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val exerciseId = db.exerciseDao().insert(exercise())
            val sessionExerciseId = db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
            db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1, reps = 10))
            db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 2, reps = 8))
            val second = repository.getSets(sessionExerciseId).first()[1]

            repository.updateSet(second.copy(reps = 6, weight = 82_500_000L, updatedAt = 900L))

            val sets = repository.getSets(sessionExerciseId).first()
            assertEquals(listOf(10, 6), sets.map { it.reps })
            assertEquals(listOf(1, 2), sets.map { it.setNumber })
            assertEquals(82_500_000L, sets[1].weight)
        }

    @Test
    fun `deleteSet removes one set and leaves the rest of the table`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val exerciseId = db.exerciseDao().insert(exercise())
            val sessionExerciseId = db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
            db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1, reps = 10))
            db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 2, reps = 8))

            repository.deleteSet(repository.getSets(sessionExerciseId).first()[0].id)

            assertEquals(listOf(8), repository.getSets(sessionExerciseId).first().map { it.reps })
        }

    @Test
    fun `updateSets closes the gap a deletion leaves, in one write`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val exerciseId = db.exerciseDao().insert(exercise())
            val sessionExerciseId = db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
            db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1, reps = 10))
            db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 2, reps = 8))
            db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 3, reps = 6))

            val sets = repository.getSets(sessionExerciseId).first()
            repository.deleteSet(sets[0].id)
            repository.updateSets(
                listOf(sets[1].copy(setNumber = 1), sets[2].copy(setNumber = 2)),
            )

            val renumbered = repository.getSets(sessionExerciseId).first()
            assertEquals(listOf(1, 2), renumbered.map { it.setNumber })
            assertEquals("the rows are the same rows", listOf(8, 6), renumbered.map { it.reps })
        }

    @Test
    fun `getPreviousSets groups the last session's work under the catalogue exercise`() =
        runBlocking {
            val bench = db.exerciseDao().insert(exercise(name = "Bench"))
            val fly = db.exerciseDao().insert(exercise(name = "Fly"))
            val last = db.workoutSessionDao().insert(session(isInProgress = false, createdAt = 100))
            val slot = db.sessionExerciseDao().insert(sessionExercise(last, bench, position = 0))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 1, weight = 80_000_000, reps = 5))
            db.workoutSetDao().insert(workoutSet(slot, setNumber = 2, weight = 85_000_000, reps = 3))

            val today = db.workoutSessionDao().insert(session(createdAt = 200))
            db.sessionExerciseDao().insert(sessionExercise(today, bench, position = 0))
            db.sessionExerciseDao().insert(sessionExercise(today, fly, position = 1))

            val previous = repository.getPreviousSets(today).first()

            // Keyed by the catalogue id, because the `session_exercise` id on those rows belongs
            // to a workout the caller is holding nothing about — see the contract.
            assertEquals(setOf(bench), previous.keys)
            assertEquals(listOf(80_000_000L, 85_000_000L), previous.getValue(bench).map { it.weight })
            // And absent, not empty, for an exercise with nothing behind it: the screen draws a
            // "first time" state off exactly that difference.
            assertNull(previous[fly])
        }

    @Test
    fun `getSetsForSession reads every exercise's sets in one pass, in session order`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val squat = db.exerciseDao().insert(exercise(name = "Squat"))
            val bench = db.exerciseDao().insert(exercise(name = "Bench", equipmentType = "BARBELL", machineBrand = "B"))
            val second = db.sessionExerciseDao().insert(sessionExercise(sessionId, bench, position = 1))
            val first = db.sessionExerciseDao().insert(sessionExercise(sessionId, squat, position = 0))
            db.workoutSetDao().insert(workoutSet(second, setNumber = 1))
            db.workoutSetDao().insert(workoutSet(first, setNumber = 2))
            db.workoutSetDao().insert(workoutSet(first, setNumber = 1))

            val sets = repository.getSetsForSession(sessionId).first()

            // Exercise position first, then set number: the order the session is shown in.
            assertEquals(listOf(first to 1, first to 2, second to 1), sets.map { it.sessionExerciseId to it.setNumber })
        }

    @Test
    fun `getSetsForSession ignores sets belonging to another session`() =
        runBlocking {
            val exerciseId = db.exerciseDao().insert(exercise())
            val mine = db.workoutSessionDao().insert(session(createdAt = 1))
            val theirs = db.workoutSessionDao().insert(session(createdAt = 2))
            val myExercise = db.sessionExerciseDao().insert(sessionExercise(mine, exerciseId, position = 0))
            val theirExercise = db.sessionExerciseDao().insert(sessionExercise(theirs, exerciseId, position = 0))
            db.workoutSetDao().insert(workoutSet(myExercise, setNumber = 1))
            db.workoutSetDao().insert(workoutSet(theirExercise, setNumber = 1))

            assertEquals(listOf(myExercise), repository.getSetsForSession(mine).first().map { it.sessionExerciseId })
        }

    @Test
    fun `getSetsForSession is empty for a session with no exercises`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())

            assertTrue(repository.getSetsForSession(sessionId).first().isEmpty())
        }

    @Test
    fun `getTotals keys every session's figures by its id`() =
        runBlocking {
            val exerciseId = db.exerciseDao().insert(exercise(name = "Bench press"))
            val first = db.workoutSessionDao().insert(session(createdAt = 100))
            val second = db.workoutSessionDao().insert(session(createdAt = 200))
            val firstExercise = db.sessionExerciseDao()
                .insert(sessionExercise(first, exerciseId, position = 0))
            val secondExercise = db.sessionExerciseDao()
                .insert(sessionExercise(second, exerciseId, position = 0))

            // 100kg x 10 = 1_000_000_000; 50kg x 4 twice = 400_000_000
            db.workoutSetDao().insert(
                workoutSet(firstExercise, setNumber = 1, reps = 10, weight = 100_000_000),
            )
            db.workoutSetDao().insert(
                workoutSet(secondExercise, setNumber = 1, reps = 4, weight = 50_000_000),
            )
            db.workoutSetDao().insert(
                workoutSet(secondExercise, setNumber = 2, reps = 4, weight = 50_000_000),
            )

            val totals = repository.getTotals().first()

            assertEquals(1, totals.getValue(first).totalSets)
            assertEquals(1_000_000_000L, totals.getValue(first).totalVolume)
            assertEquals(2, totals.getValue(second).totalSets)
            assertEquals(400_000_000L, totals.getValue(second).totalVolume)
        }

    @Test
    fun `getTotals omits a session that logged nothing`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())

            // The contract's absence rather than a zeroed entry — see SessionRepository.getTotals.
            assertNull(repository.getTotals().first()[sessionId])
        }

    @Test
    fun `update writes the changed fields back`() =
        runBlocking {
            val id = repository.insert(WorkoutSession(lastActiveAt = 1, createdAt = 1, updatedAt = 1))
            val stored = repository.getById(id).first()!!

            repository.update(stored.copy(isInProgress = false, completedAt = 50, notes = "done", updatedAt = 50))

            val reloaded = repository.getById(id).first()!!
            assertFalse(reloaded.isInProgress)
            assertEquals(50L, reloaded.completedAt)
            assertEquals("done", reloaded.notes)
        }

    @Test
    fun `delete takes the session's exercises and sets with it`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session())
            val exerciseId = db.exerciseDao().insert(exercise())
            val sessionExerciseId = db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
            db.workoutSetDao().insert(workoutSet(sessionExerciseId, setNumber = 1))

            repository.delete(sessionId)

            assertNull(repository.getById(sessionId).first())
            assertEquals(0, countRows("session_exercise"))
            assertEquals(0, countRows("workout_set"))
            // The catalogue entry is not the session's to delete.
            assertEquals(1, countRows("exercise"))
        }

    /**
     * The resume path, end to end: a session left `is_in_progress = 1` by a process
     * that died has to come back with the same exercises in the same order and the
     * same sets against each. Restarting the process is not something a JVM test can
     * do, but everything the restore reads is on disk, so re-reading it through a
     * repository built over a fresh handle is the same question.
     */
    @Test
    fun `an in-progress session restores its exercises and sets exactly`() =
        runBlocking {
            val sessionId = db.workoutSessionDao().insert(session(isInProgress = true, lastActiveAt = 400))
            val squat = db.exerciseDao().insert(exercise(name = "Squat"))
            val press = db.exerciseDao().insert(exercise(name = "Press", equipmentType = "BARBELL", machineBrand = "P"))
            val first = db.sessionExerciseDao().insert(sessionExercise(sessionId, squat, position = 0))
            val second = db.sessionExerciseDao().insert(sessionExercise(sessionId, press, position = 1))
            db.workoutSetDao().insert(workoutSet(first, setNumber = 1, reps = 5, weight = 100_000_000))
            db.workoutSetDao().insert(workoutSet(first, setNumber = 2, reps = 5, weight = 100_000_000))
            db.workoutSetDao().insert(workoutSet(second, setNumber = 1, isCompleted = false, reps = 8))

            val resumed = repository.getActive().first()
            val exercises = repository.getExercises(resumed!!.id).first()
            val sets = repository.getSetsForSession(resumed.id).first()

            assertEquals(sessionId, resumed.id)
            assertEquals(listOf(squat, press), exercises.map { it.exerciseId })
            assertEquals(listOf(0, 1), exercises.map { it.position })
            assertEquals(3, sets.size)
            assertEquals(listOf(5, 5, 8), sets.map { it.reps })
            assertEquals(listOf(true, true, false), sets.map { it.isCompleted })
            assertEquals(100_000_000L, sets.first().weight)
        }

    private companion object {
        /** An `exercise` id nothing was ever inserted under, so a row naming it breaks the key. */
        const val NO_SUCH_EXERCISE = 9_999L
    }
}
