package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrDetectionUseCaseTest {

    private val clock = MutableClock(now = 1_000L)
    private val sessions = FakeSessionRepository()
    private val records = FakePersonalRecordRepository()
    private val preferences = FakeUserPreferencesRepository()
    private val detectPrs = PrDetectionUseCase(sessions, records, preferences, clock)

    @Test
    fun `writes one row per pr type actually broken, not one per set`() = runTest {
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
    fun `a cardio set with a distance but no duration yields a distance record and no pace record`() = runTest {
        val exerciseId = 20L
        val sessionId = startSession(exerciseId)
        addCardioSet(sessionId, distance = 500_000, duration = null)

        val created = detectPrs(sessionId)

        assertEquals(listOf(PrType.MAX_DISTANCE), created.map { it.type })
    }

    @Test
    fun `a slower run does not beat an existing best pace`() = runTest {
        val exerciseId = 30L
        val fastSession = startSession(exerciseId)
        addCardioSet(fastSession, distance = 1_000, duration = 300)
        // A distance and a duration on the same set also breaks the distance record; only
        // the pace side of this is under test.
        detectPrs(fastSession)

        val slowSession = startSession(exerciseId)
        // Same distance, more time — a slower pace, which must not overwrite the faster one.
        addCardioSet(slowSession, distance = 1_000, duration = 400)
        val slowRunResult = detectPrs(slowSession)

        assertTrue(slowRunResult.none { it.type == PrType.BEST_PACE })
        assertEquals(300L, records.inserted.single { it.type == PrType.BEST_PACE }.duration)
    }

    @Test
    fun `a faster run does beat an existing best pace`() = runTest {
        val exerciseId = 31L
        val slowSession = startSession(exerciseId)
        addCardioSet(slowSession, distance = 1_000, duration = 400)
        detectPrs(slowSession)

        val fastSession = startSession(exerciseId)
        addCardioSet(fastSession, distance = 1_000, duration = 300)
        val fastRunResult = detectPrs(fastSession)

        assertEquals(listOf(PrType.BEST_PACE), fastRunResult.map { it.type })
    }

    @Test
    fun `re-processing the same finished session twice creates no duplicate rows`() = runTest {
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
    fun `tying an existing record does not create a new one, beating it does`() = runTest {
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
    fun `switching the formula changes future estimates without rewriting past records`() = runTest {
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
    fun `a session with no completed sets detects nothing`() = runTest {
        val sessionId = startSession(exerciseId = 70L)

        assertEquals(emptyList<Any>(), detectPrs(sessionId))
        assertTrue(records.inserted.isEmpty())
    }

    @Test
    fun `the max volume record is anchored on the exercise's last completed set`() = runTest {
        val exerciseId = 80L
        val sessionId = startSession(exerciseId)
        addSet(sessionId, weight = 50_000_000, reps = 10, completedAt = 5L)
        val laterSetId = addSet(sessionId, weight = 40_000_000, reps = 10, completedAt = 9L)

        val created = detectPrs(sessionId)

        val maxVolume = created.single { it.type == PrType.MAX_VOLUME }
        assertEquals(laterSetId, maxVolume.workoutSetId)
        assertNull(maxVolume.reps)
    }

    /** Starts a real session row and attaches [exerciseId] to it, returning the session's id. */
    private suspend fun startSession(exerciseId: Long): Long {
        val sessionId = sessions.insert(WorkoutSession(lastActiveAt = 0L, createdAt = 0L, updatedAt = 0L))
        val sessionExerciseId = sessions.addExercise(sessionId = sessionId, exerciseId = exerciseId)
        sessionExerciseIds[sessionId] = sessionExerciseId
        return sessionId
    }

    /** Which `session_exercise` row [startSession] created for a given session. */
    private val sessionExerciseIds = mutableMapOf<Long, Long>()

    private fun addSet(sessionId: Long, weight: Long?, reps: Int?, completedAt: Long): Long =
        sessions.addSet(
            sessionExerciseIds.getValue(sessionId),
            WorkoutSet(
                sessionExerciseId = sessionExerciseIds.getValue(sessionId),
                setNumber = 0,
                weight = weight,
                reps = reps,
                isCompleted = true,
                completedAt = completedAt,
                createdAt = completedAt,
                updatedAt = completedAt,
            ),
        ).id

    private fun addCardioSet(sessionId: Long, distance: Long?, duration: Long?) {
        val sessionExerciseId = sessionExerciseIds.getValue(sessionId)
        sessions.addSet(
            sessionExerciseId,
            WorkoutSet(
                sessionExerciseId = sessionExerciseId,
                setNumber = 0,
                distance = distance,
                duration = duration,
                isCompleted = true,
                completedAt = 1L,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }
}
