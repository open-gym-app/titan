package com.titan.core.domain.usecase

import com.titan.core.domain.integration.FakeExerciseRepository
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.model.holdLoad
import com.titan.core.domain.model.paceOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrDetectionUseCaseTest {

    private val sessions = FakeSessionRepository()
    private val exercises = FakeExerciseRepository()
    private val records = FakePersonalRecordRepository()
    private val preferences = FakeUserPreferencesRepository()
    private val detectPrs = PrDetectionUseCase(sessions, exercises, records, preferences)

    @Test
    fun `writes one row per pr type actually broken, not one per set`() =
        runTest {
            val exerciseId = 10L
            val sessionId = startSession(exerciseId)
            // The reps record and the weight record are on different sets, and the estimated
            // 1RM leans on the second: 110 kg for 3 is a higher estimate than 100 kg for 5.
            addSet(sessionId, weight = 100_000_000, reps = 5, completedAt = 1L)
            addSet(sessionId, weight = 110_000_000, reps = 3, completedAt = 2L)

            val created = detectPrs(sessionId)

            val brokenTypes = setOf(PrType.MAX_WEIGHT, PrType.MAX_REPS, PrType.MAX_VOLUME, PrType.ESTIMATED_1RM)
            assertEquals(brokenTypes, created.map { it.type }.toSet())
            assertEquals(4, created.size)

            val maxWeight = created.single { it.type == PrType.MAX_WEIGHT }
            assertEquals(110_000_000L, maxWeight.weight)

            val maxReps = created.single { it.type == PrType.MAX_REPS }
            assertEquals(5, maxReps.reps)

            // 100M × 5 + 110M × 3 = 830M, anchored on the later of the two sets.
            val maxVolume = created.single { it.type == PrType.MAX_VOLUME }
            assertEquals(830_000_000L, maxVolume.weight)

            val estimated1Rm = created.single { it.type == PrType.ESTIMATED_1RM }
            assertEquals(121_000_000.0, estimated1Rm.estimated1Rm!!.toDouble(), 200.0)
        }

    @Test
    fun `records written before the store refused are still reported`() =
        runTest {
            // The rows the first two writes put on file are real, and a re-run beats them
            // rather than re-detecting them. A caller told "nothing was detected" would
            // therefore never hear about them again — so the throw must not take them with it.
            val sessionId = startSession(exerciseId = 10L)
            addSet(sessionId, weight = 100_000_000, reps = 5, completedAt = 1L)
            addSet(sessionId, weight = 110_000_000, reps = 3, completedAt = 2L)
            records.refuseWriteAfter = 2

            val detected = mutableListOf<PersonalRecord>()
            val failure = runCatching { detectPrs(sessionId, detected) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals(records.inserted, detected)
            assertEquals(2, detected.size)
        }

    @Test
    fun `a session of holds earns its longest hold, and no record a hold cannot have`() =
        runTest {
            // A hold carries no reps and no distance, so none of the dimension-keyed bests has a
            // candidate — and an empty candidate list must be a "no record", not a forced read of
            // a missing value. What it does have is a time.
            val sessionId = startSession(exerciseId = 40L, type = ExerciseType.ISOMETRIC)
            addCardioSet(sessionId, distance = null, duration = 90)
            addCardioSet(sessionId, distance = null, duration = 75)

            val created = detectPrs(sessionId)

            assertEquals(listOf(PrType.MAX_DURATION), created.map { it.type })
            assertEquals(90L, created.single().duration)
        }

    @Test
    fun `a two minute plank then a two and a half minute one writes two duration records`() =
        runTest {
            val exerciseId = 41L
            val first = startSession(exerciseId, type = ExerciseType.ISOMETRIC)
            addCardioSet(first, distance = null, duration = 120, completedAt = 1L)
            detectPrs(first)

            val second = startSession(exerciseId, type = ExerciseType.ISOMETRIC)
            addCardioSet(second, distance = null, duration = 150, completedAt = 2L)
            detectPrs(second)

            assertEquals(
                listOf(120L, 150L),
                records.inserted.filter { it.type == PrType.MAX_DURATION }.map { it.duration },
            )
            // The second is an improvement of thirty seconds on the first.
            val newest = records.getRecordsPage(type = PrType.MAX_DURATION, offset = 0, limit = 1).single()
            assertEquals(30L, newest.improvement)
        }

    @Test
    fun `a weighted plank earns a weighted hold and a duration record, and never a weight record`() =
        runTest {
            val exerciseId = 42L
            val unweighted = startSession(exerciseId, type = ExerciseType.ISOMETRIC)
            addCardioSet(unweighted, distance = null, duration = 45, completedAt = 1L)
            detectPrs(unweighted)

            val weighted = startSession(exerciseId, type = ExerciseType.ISOMETRIC)
            addCardioSet(weighted, distance = null, duration = 60, weight = 20_000_000, completedAt = 2L)
            val created = detectPrs(weighted)

            assertEquals(setOf(PrType.MAX_WEIGHTED_HOLD, PrType.MAX_DURATION), created.map { it.type }.toSet())
            val hold = created.single { it.type == PrType.MAX_WEIGHTED_HOLD }
            // Stored as the product, with the seconds beside it so the load can be read back.
            assertEquals(20_000_000L * 60, hold.weight)
            assertEquals(60L, hold.duration)
            assertEquals(20_000_000L, hold.holdLoad)
        }

    @Test
    fun `a shorter weighted hold beats the load-seconds without beating the longest hold`() =
        runTest {
            val exerciseId = 43L
            val long = startSession(exerciseId, type = ExerciseType.ISOMETRIC)
            addCardioSet(long, distance = null, duration = 120, weight = 10_000_000, completedAt = 1L)
            detectPrs(long)

            // 20 kg for 90 s is 1,800 kg·s against 1,200 kg·s — more load held, for less time.
            val heavy = startSession(exerciseId, type = ExerciseType.ISOMETRIC)
            addCardioSet(heavy, distance = null, duration = 90, weight = 20_000_000, completedAt = 2L)
            val created = detectPrs(heavy)

            assertEquals(listOf(PrType.MAX_WEIGHTED_HOLD), created.map { it.type })
        }

    @Test
    fun `a squat set with a duration recorded earns no duration record`() =
        runTest {
            val sessionId = startSession(exerciseId = 44L, type = ExerciseType.STRENGTH)
            addCardioSet(sessionId, distance = null, duration = 40, weight = 100_000_000)

            val created = detectPrs(sessionId)

            assertTrue(created.none { it.type == PrType.MAX_DURATION || it.type == PrType.MAX_WEIGHTED_HOLD })
            assertTrue(created.any { it.type == PrType.MAX_WEIGHT })
        }

    @Test
    fun `a run with a weight recorded earns no lifting record`() =
        runTest {
            // A rucksack on a run is load the validator lets through, not a lift.
            val sessionId = startSession(exerciseId = 45L, type = ExerciseType.ENDURANCE)
            addCardioSet(sessionId, distance = 500_000, duration = 1_634, weight = 10_000_000)

            val created = detectPrs(sessionId)

            assertEquals(
                setOf(PrType.MAX_DISTANCE, PrType.BEST_PACE, PrType.MAX_DURATION),
                created.map { it.type }.toSet(),
            )
        }

    @Test
    fun `an exercise the catalogue no longer holds is judged as a lift`() =
        runTest {
            // Never registered with the catalogue: the fallback is the strength kinds, which a
            // timed set has no candidate for.
            val sessionId = startSession(exerciseId = 46L, type = null)
            addCardioSet(sessionId, distance = 500_000, duration = 1_634)

            assertEquals(emptyList<Any>(), detectPrs(sessionId))
        }

    @Test
    fun `a longer run at a better pace is a new best pace, compared by pace and not by time`() =
        runTest {
            val exerciseId = 47L
            // 5 km in 27:14 — 5:27 per kilometre.
            val fiveK = startSession(exerciseId, type = ExerciseType.ENDURANCE)
            addCardioSet(fiveK, distance = 500_000, duration = 1_634, completedAt = 1L)
            detectPrs(fiveK)

            // 10 km at 5:10 per kilometre: nearly twice the time, and faster.
            val tenK = startSession(exerciseId, type = ExerciseType.ENDURANCE)
            addCardioSet(tenK, distance = 1_000_000, duration = 3_100, completedAt = 2L)
            val created = detectPrs(tenK)

            assertTrue(created.any { it.type == PrType.BEST_PACE })
            val newest = records.getRecordsPage(type = PrType.BEST_PACE, offset = 0, limit = 1).single()
            // What it beat is a pace, not a total time, and no duration difference is claimed.
            assertEquals(paceOf(durationSeconds = 1_634, distanceCentimetres = 500_000), newest.previousPace)
            assertNull(newest.improvement)
        }

    @Test
    fun `a shorter run at a worse pace is not a best pace, however little time it took`() =
        runTest {
            val exerciseId = 48L
            val tenK = startSession(exerciseId, type = ExerciseType.ENDURANCE)
            addCardioSet(tenK, distance = 1_000_000, duration = 3_100, completedAt = 1L)
            detectPrs(tenK)

            // 5 km in 27:14 is much less time than 51:40, and a slower pace.
            val fiveK = startSession(exerciseId, type = ExerciseType.ENDURANCE)
            addCardioSet(fiveK, distance = 500_000, duration = 1_634, completedAt = 2L)

            assertTrue(detectPrs(fiveK).none { it.type == PrType.BEST_PACE })
        }

    @Test
    fun `re-running detection over a timed session inserts nothing`() =
        runTest {
            val sessionId = startSession(exerciseId = 49L, type = ExerciseType.ISOMETRIC)
            addCardioSet(sessionId, distance = null, duration = 60, weight = 20_000_000)
            detectPrs(sessionId)
            val countAfterFirst = records.inserted.size

            assertEquals(emptyList<Any>(), detectPrs(sessionId))
            assertEquals(countAfterFirst, records.inserted.size)
        }

    @Test
    fun `a cardio set with a distance but no duration yields a distance record and no pace record`() =
        runTest {
            val exerciseId = 20L
            val sessionId = startSession(exerciseId, type = ExerciseType.ENDURANCE)
            addCardioSet(sessionId, distance = 500_000, duration = null)

            val created = detectPrs(sessionId)

            assertEquals(listOf(PrType.MAX_DISTANCE), created.map { it.type })
        }

    @Test
    fun `a slower run does not beat an existing best pace`() =
        runTest {
            val exerciseId = 30L
            val fastSession = startSession(exerciseId, type = ExerciseType.ENDURANCE)
            addCardioSet(fastSession, distance = 1_000, duration = 300)
            // A distance and a duration on the same set also breaks the distance record; only
            // the pace side of this is under test.
            detectPrs(fastSession)

            val slowSession = startSession(exerciseId, type = ExerciseType.ENDURANCE)
            // Same distance, more time — a slower pace, which must not overwrite the faster one.
            addCardioSet(slowSession, distance = 1_000, duration = 400)
            val slowRunResult = detectPrs(slowSession)

            assertTrue(slowRunResult.none { it.type == PrType.BEST_PACE })
            assertEquals(300L, records.inserted.single { it.type == PrType.BEST_PACE }.duration)
        }

    @Test
    fun `a faster run does beat an existing best pace`() =
        runTest {
            val exerciseId = 31L
            val slowSession = startSession(exerciseId, type = ExerciseType.ENDURANCE)
            addCardioSet(slowSession, distance = 1_000, duration = 400)
            detectPrs(slowSession)

            val fastSession = startSession(exerciseId, type = ExerciseType.ENDURANCE)
            addCardioSet(fastSession, distance = 1_000, duration = 300)
            val fastRunResult = detectPrs(fastSession)

            assertEquals(listOf(PrType.BEST_PACE), fastRunResult.map { it.type })
        }

    @Test
    fun `re-processing the same finished session twice creates no duplicate rows`() =
        runTest {
            val exerciseId = 40L
            val sessionId = startSession(exerciseId)
            addSet(sessionId, weight = 100_000_000, reps = 5, completedAt = 1L)

            val first = detectPrs(sessionId)
            assertTrue(first.isNotEmpty())
            val countAfterFirst = records.inserted.size

            val second = detectPrs(sessionId)

            assertTrue("re-processing wrote new rows: $second", second.isEmpty())
            assertEquals(countAfterFirst, records.inserted.size)
        }

    @Test
    fun `tying an existing record does not create a new one, beating it does`() =
        runTest {
            val exerciseId = 50L
            val firstSession = startSession(exerciseId)
            addSet(firstSession, weight = 100_000_000, reps = 5, completedAt = 1L)
            detectPrs(firstSession)

            val tyingSession = startSession(exerciseId)
            addSet(tyingSession, weight = 100_000_000, reps = 5, completedAt = 1L)
            val tyingResult = detectPrs(tyingSession)
            assertTrue(tyingResult.none { it.type == PrType.MAX_WEIGHT })

            val beatingSession = startSession(exerciseId)
            addSet(beatingSession, weight = 110_000_000, reps = 5, completedAt = 1L)
            val beatingResult = detectPrs(beatingSession)
            assertEquals(110_000_000L, beatingResult.single { it.type == PrType.MAX_WEIGHT }.weight)
        }

    @Test
    fun `only a record that found one on file to improve on is reported as beaten`() =
        runTest {
            // The first session of an exercise writes a baseline of every kind it has, with
            // nothing to compare against: written, and none of it beaten.
            val bench = 60L
            val firstSession = startSession(bench)
            addSet(firstSession, weight = 100_000_000, reps = 5, completedAt = 1L)
            val firstBeaten = mutableListOf<PersonalRecord>()
            val firstWritten = detectPrs(firstSession, beaten = firstBeaten)
            assertEquals(4, firstWritten.size)
            assertTrue(firstBeaten.isEmpty())

            // A heavier set on the same exercise improves three kinds and ties the fourth; a
            // squat done for the first time in the same session is baselines again.
            val squat = 61L
            val secondSession = startSession(bench)
            addSet(secondSession, weight = 110_000_000, reps = 5, completedAt = 2L)
            exercises.update(Exercise(id = squat, name = "Squat", type = ExerciseType.STRENGTH))
            val squatSlot = sessions.addExercise(sessionId = secondSession, exerciseId = squat)
            sessions.addSet(
                squatSlot,
                WorkoutSet(
                    sessionExerciseId = squatSlot,
                    setNumber = 0,
                    weight = 140_000_000,
                    reps = 5,
                    isCompleted = true,
                    completedAt = 3L,
                    createdAt = 3L,
                    updatedAt = 3L,
                ),
            )
            val secondBeaten = mutableListOf<PersonalRecord>()
            val secondWritten = detectPrs(secondSession, beaten = secondBeaten)

            assertEquals(
                setOf(PrType.MAX_WEIGHT, PrType.MAX_VOLUME, PrType.ESTIMATED_1RM),
                secondBeaten.map { it.type }.toSet(),
            )
            assertTrue(secondBeaten.all { it.exerciseId == bench })
            assertTrue(secondWritten.containsAll(secondBeaten))
        }

    @Test
    fun `switching the formula changes future estimates without rewriting past records`() =
        runTest {
            val exerciseId = 60L

            val firstSession = startSession(exerciseId)
            addSet(firstSession, weight = 100_000_000, reps = 20, completedAt = 1L)
            detectPrs(firstSession)
            val epleyEstimate = records.inserted.single { it.type == PrType.ESTIMATED_1RM }
            // 100M × (1 + 20/30) ≈ 166.667M.
            assertEquals(166_666_667.0, epleyEstimate.estimated1Rm!!.toDouble(), 200.0)

            preferences.setOneRepMaxFormula(OneRepMaxFormula.BRZYCKI)
            val secondSession = startSession(exerciseId)
            // Same weight and reps: what changes is purely the formula.
            addSet(secondSession, weight = 100_000_000, reps = 20, completedAt = 1L)
            val secondResult = detectPrs(secondSession)
            // 100M × 36/17 ≈ 211.76M — well above the Epley figure, so it is written as new best.
            val brzyckiEstimate = secondResult.single { it.type == PrType.ESTIMATED_1RM }
            assertEquals(211_764_706.0, brzyckiEstimate.estimated1Rm!!.toDouble(), 200.0)

            // The first session's own row is untouched — its formula was Epley when it was
            // written and stays that value now.
            val estimated1RmRows = records.inserted.filter { it.type == PrType.ESTIMATED_1RM }
            assertEquals(2, estimated1RmRows.size)
            assertEquals(166_666_667.0, estimated1RmRows.first().estimated1Rm!!.toDouble(), 200.0)
        }

    @Test
    fun `a session with no completed sets detects nothing`() =
        runTest {
            val sessionId = startSession(exerciseId = 70L)

            assertEquals(emptyList<Any>(), detectPrs(sessionId))
            assertTrue(records.inserted.isEmpty())
        }

    @Test
    fun `a warm-up set does not set or break any personal record`() =
        runTest {
            val exerciseId = 90L
            val sessionId = startSession(exerciseId)
            // A heavy single warm-up rep, easy for its own sake, followed by the real work set.
            addSet(sessionId, weight = 200_000_000, reps = 1, completedAt = 1L, setType = SetType.WARMUP)
            addSet(sessionId, weight = 100_000_000, reps = 5, completedAt = 2L)

            val created = detectPrs(sessionId)

            val maxWeight = created.single { it.type == PrType.MAX_WEIGHT }
            assertEquals(100_000_000L, maxWeight.weight)
            // 100M × 5 only — the warm-up's weight × reps must not be in the sum.
            val maxVolume = created.single { it.type == PrType.MAX_VOLUME }
            assertEquals(500_000_000L, maxVolume.weight)
        }

    @Test
    fun `a session that is only warm-ups detects nothing`() =
        runTest {
            val exerciseId = 91L
            val sessionId = startSession(exerciseId)
            addSet(sessionId, weight = 200_000_000, reps = 1, completedAt = 1L, setType = SetType.WARMUP)

            assertEquals(emptyList<Any>(), detectPrs(sessionId))
            assertTrue(records.inserted.isEmpty())
        }

    @Test
    fun `the max volume record is anchored on the exercise's last completed set`() =
        runTest {
            val exerciseId = 80L
            val sessionId = startSession(exerciseId)
            addSet(sessionId, weight = 50_000_000, reps = 10, completedAt = 5L)
            val laterSetId = addSet(sessionId, weight = 40_000_000, reps = 10, completedAt = 9L)

            val created = detectPrs(sessionId)

            val maxVolume = created.single { it.type == PrType.MAX_VOLUME }
            assertEquals(laterSetId, maxVolume.workoutSetId)
            assertNull(maxVolume.reps)
        }

    /**
     * Starts a real session row and attaches [exerciseId] to it, returning the session's id.
     *
     * The exercise is put in the catalogue as [type] first — a lift unless a test says otherwise —
     * because the type is what decides which kinds of record it can earn. Null leaves it out.
     */
    private suspend fun startSession(
        exerciseId: Long,
        type: ExerciseType? = ExerciseType.STRENGTH,
    ): Long {
        if (type != null) exercises.update(Exercise(id = exerciseId, name = "Exercise $exerciseId", type = type))
        val sessionId = sessions.insert(WorkoutSession(lastActiveAt = 0L, createdAt = 0L, updatedAt = 0L))
        val sessionExerciseId = sessions.addExercise(sessionId = sessionId, exerciseId = exerciseId)
        sessionExerciseIds[sessionId] = sessionExerciseId
        return sessionId
    }

    /** Which `session_exercise` row [startSession] created for a given session. */
    private val sessionExerciseIds = mutableMapOf<Long, Long>()

    private fun addSet(
        sessionId: Long,
        weight: Long?,
        reps: Int?,
        completedAt: Long,
        setType: SetType = SetType.NORMAL,
    ): Long =
        sessions.addSet(
            sessionExerciseIds.getValue(sessionId),
            WorkoutSet(
                sessionExerciseId = sessionExerciseIds.getValue(sessionId),
                setNumber = 0,
                weight = weight,
                reps = reps,
                setType = setType,
                isCompleted = true,
                completedAt = completedAt,
                createdAt = completedAt,
                updatedAt = completedAt,
            ),
        ).id

    /** A timed set: a run, a ride or a hold — the last with a load on it when [weight] is given. */
    private fun addCardioSet(
        sessionId: Long,
        distance: Long?,
        duration: Long?,
        weight: Long? = null,
        completedAt: Long = 1L,
    ) {
        val sessionExerciseId = sessionExerciseIds.getValue(sessionId)
        sessions.addSet(
            sessionExerciseId,
            WorkoutSet(
                sessionExerciseId = sessionExerciseId,
                setNumber = 0,
                weight = weight,
                distance = distance,
                duration = duration,
                isCompleted = true,
                completedAt = completedAt,
                createdAt = completedAt,
                updatedAt = completedAt,
            ),
        )
    }
}
