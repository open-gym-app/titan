package com.titan.core.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two scalar period aggregates the quest board reads.
 *
 * They exist because a quest period is not always a whole number of the Monday weeks
 * `getTrainingByWeek` and `getVolumeByWeek` bucket by — a **calendar month** almost never is —
 * so the cases that matter here are the ragged boundaries. The fixture is anchored on real
 * dates and every expected figure is written as a literal, worked out by hand rather than
 * recomputed with the formula the query uses.
 */
class QuestAggregateDaoTest : RoomTestBase() {

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L

        /** 2025-09-01, a Monday and the first of the month. */
        const val SEP_1 = 20_332L
        const val SEP_30 = SEP_1 + 29
        const val AUG_31 = SEP_1 - 1
        const val OCT_1 = SEP_1 + 30

        /** Midday, so a day's rows are never near a boundary by accident. */
        fun at(epochDay: Long): Long = epochDay * MILLIS_PER_DAY + 12 * 3_600_000L

        fun kg(value: Long): Long = value * 1_000_000L
    }

    private val sessionDao by lazy { db.workoutSessionDao() }
    private val setDao by lazy { db.workoutSetDao() }

    // ─── Sessions ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a period with no training counts zero rather than returning nothing`() =
        runBlocking {
            assertEquals(0, sessionsIn(SEP_1, OCT_1))
        }

    @Test
    fun `every finished session in the period is counted`() =
        runBlocking {
            finishSessionOn(SEP_1)
            finishSessionOn(SEP_1 + 10)
            finishSessionOn(SEP_30)

            assertEquals(3, sessionsIn(SEP_1, OCT_1))
        }

    @Test
    fun `two sessions on one day count twice`() =
        runBlocking {
            // The one place this differs from `getTrainingByWeek`'s training-day figure: a quest
            // to train five times is met by five workouts however they fall across the days.
            finishSessionOn(SEP_1)
            finishSessionOn(SEP_1)

            assertEquals(2, sessionsIn(SEP_1, OCT_1))
        }

    @Test
    fun `a month counts its own first and last day and neither neighbour`() =
        runBlocking {
            // The ragged boundary. September 2025 closes on a Tuesday, so the 30th sits in a
            // week that runs into October — and summing the Monday-bucketed series would have
            // swept the 1st of October in with it.
            finishSessionOn(AUG_31)
            finishSessionOn(SEP_1)
            finishSessionOn(SEP_30)
            finishSessionOn(OCT_1)

            assertEquals(2, sessionsIn(SEP_1, OCT_1))
        }

    @Test
    fun `a session still in progress is not counted`() =
        runBlocking {
            sessionDao.insert(session(isInProgress = true, lastActiveAt = at(SEP_1), createdAt = at(SEP_1)))

            assertEquals(0, sessionsIn(SEP_1, OCT_1))
        }

    // ─── Volume ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a period with no lifting sums zero rather than null`() =
        runBlocking {
            // `SUM` over no rows is NULL, and a period that lifted nothing moved zero.
            assertEquals(0L, volumeIn(SEP_1, OCT_1))
        }

    @Test
    fun `volume is weight times reps over the period's completed sets`() =
        runBlocking {
            val slot = slotOn(SEP_1)
            setDao.insert(workoutSet(slot, setNumber = 1, completedAt = at(SEP_1), reps = 10, weight = kg(100)))
            setDao.insert(workoutSet(slot, setNumber = 2, completedAt = at(SEP_1), reps = 5, weight = kg(60)))

            // 100 kg x 10 + 60 kg x 5 = 1,300 kg.
            assertEquals(kg(1_300), volumeIn(SEP_1, OCT_1))
        }

    @Test
    fun `a set that was not completed lifts nothing`() =
        runBlocking {
            val slot = slotOn(SEP_1)
            setDao.insert(
                workoutSet(
                    slot,
                    setNumber = 1,
                    isCompleted = false,
                    completedAt = at(SEP_1),
                    reps = 10,
                    weight = kg(100),
                ),
            )

            assertEquals(0L, volumeIn(SEP_1, OCT_1))
        }

    @Test
    fun `a bodyweight set with no weight on it lifts nothing rather than failing`() =
        runBlocking {
            val slot = slotOn(SEP_1)
            setDao.insert(workoutSet(slot, setNumber = 1, completedAt = at(SEP_1), reps = 20, weight = null))

            assertEquals(0L, volumeIn(SEP_1, OCT_1))
        }

    @Test
    fun `a month's volume takes its own first and last day and neither neighbour`() =
        runBlocking {
            listOf(AUG_31, SEP_1, SEP_30, OCT_1).forEach { day ->
                val slot = slotOn(day)
                setDao.insert(workoutSet(slot, setNumber = 1, completedAt = at(day), reps = 10, weight = kg(100)))
            }

            // Only September's two days: 1,000 kg each.
            assertEquals(kg(2_000), volumeIn(SEP_1, OCT_1))
        }

    @Test
    fun `the scalar agrees with the weekly series over a range that is whole weeks`() =
        runBlocking {
            // Where both are defined they must be the same figure, or the board and the chart
            // would disagree about a week the user can see on both.
            listOf(SEP_1, SEP_1 + 3, SEP_1 + 6).forEach { day ->
                val slot = slotOn(day)
                setDao.insert(workoutSet(slot, setNumber = 1, completedAt = at(day), reps = 8, weight = kg(80)))
            }

            val weekEnd = SEP_1 + 7
            val bucketed = setDao.getVolumeByWeek(SEP_1, weekEnd).first().sumOf { it.totalVolume }
            assertEquals(bucketed, volumeIn(SEP_1, weekEnd))

            val trainedByWeek = sessionDao.getTrainingByWeek(SEP_1, weekEnd).first().sumOf { it.sessionCount }
            assertEquals(trainedByWeek, sessionsIn(SEP_1, weekEnd))
        }

    // ─── Fixture ────────────────────────────────────────────────────────────────────────

    private suspend fun sessionsIn(
        startEpochDay: Long,
        endEpochDayExclusive: Long,
    ): Int = sessionDao.countFinishedIn(startEpochDay, endEpochDayExclusive).first()

    private suspend fun volumeIn(
        startEpochDay: Long,
        endEpochDayExclusive: Long,
    ): Long = setDao.getVolumeIn(startEpochDay, endEpochDayExclusive).first()

    /** A session closed out on [epochDay], the state `FinishSessionUseCase` leaves. */
    private suspend fun finishSessionOn(epochDay: Long): Long =
        sessionDao.insert(
            session(
                isInProgress = false,
                lastActiveAt = at(epochDay),
                createdAt = at(epochDay),
                completedAt = at(epochDay),
            ),
        )

    /** A session finished on [epochDay] with one exercise on it, returning the slot sets go under. */
    private suspend fun slotOn(epochDay: Long): Long {
        val exerciseId = db.exerciseDao().insert(exercise())
        val sessionId = finishSessionOn(epochDay)
        return db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = 0))
    }
}
