package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.AttemptSet
import com.opengym.core.domain.model.ExerciseAttempt
import com.opengym.core.domain.model.ExerciseEstimate
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * One exercise's progression, against a fixture whose figures are written out as literals with
 * the arithmetic beside them — a test that recomputed an estimate with `OneRepMaxCalculator`
 * would agree with whatever that formula became.
 */
class ExerciseProgressionUseCaseTest {

    private companion object {
        const val BENCH = 1L
        const val SQUAT = 2L

        /** 2024-10-07, a Monday, matching the other progression tests' anchor. */
        val DAY_1: LocalDate = LocalDate.of(2024, 10, 7)
        val PERIOD: ClosedRange<LocalDate> = DAY_1..DAY_1.plusDays(27)

        const val MILLIS_PER_DAY = 86_400_000L

        /** Kilograms in the canonical storage unit, milligrams. */
        fun kg(value: Long): Long = value * 1_000_000L

        /** Epoch millis for midday on the day [days] after [DAY_1]. */
        fun at(days: Long): Long = (DAY_1.toEpochDay() + days) * MILLIS_PER_DAY + 12 * 3_600_000L
    }

    private val progressionRepository = FakeProgressionRepository()
    private val recordRepository = FakePersonalRecordRepository()
    private val preferences = FakeUserPreferencesRepository()

    private val useCase = ExerciseProgressionUseCase(
        progressionRepository = progressionRepository,
        personalRecordRepository = recordRepository,
        userPreferencesRepository = preferences,
    )

    @Test
    fun `the attempts and the all-time count come back as the stores hold them`() =
        runTest {
            progressionRepository.setAttempts(
                BENCH,
                attempt(sessionId = 1, day = 0, sets = 4, volume = kg(1_200), weight = kg(70), reps = 5),
                attempt(sessionId = 2, day = 7, sets = 5, volume = kg(1_400), weight = kg(75), reps = 5),
            )
            progressionRepository.setExerciseSetCount(BENCH, 142)

            val progression = useCase(BENCH, PERIOD).first()

            assertEquals(listOf(1L, 2L), progression.attempts.map(ExerciseAttempt::sessionId))
            // All-time, not the nine sets the two attempts in the window hold.
            assertEquals(142, progression.totalSets)
            assertTrue(progression.hasTrend)
            assertFalse(progression.isEmpty)
        }

    @Test
    fun `an estimate is the top set's projection, under the formula the user picked`() =
        runTest {
            progressionRepository.setAttempts(
                BENCH,
                attempt(sessionId = 1, day = 0, weight = kg(100), reps = 5),
            )

            // Epley: 100 kg x (1 + 5/30) = 116.666... kg, rounded to the stored milligram.
            assertEquals(
                listOf(ExerciseEstimate(finishedAt = at(0), estimate = 116_666_664L)),
                useCase(BENCH, PERIOD).first().estimates,
            )

            preferences.setOneRepMaxFormula(OneRepMaxFormula.BRZYCKI)

            // Brzycki: 100 kg x 36 / (37 - 5) = 112.5 kg.
            assertEquals(
                listOf(ExerciseEstimate(finishedAt = at(0), estimate = 112_500_000L)),
                useCase(BENCH, PERIOD).first().estimates,
            )
        }

    @Test
    fun `an attempt with nothing to project on is left out of the line rather than plotted as zero`() =
        runTest {
            progressionRepository.setAttempts(
                BENCH,
                // A timed hold: sets, but no weight and no reps to project from.
                attempt(sessionId = 1, day = 0, volume = 0, weight = null, reps = null),
                attempt(sessionId = 2, day = 7, weight = kg(60), reps = 10),
                // Bodyweight: reps but no weight, which is not a one-rep max either.
                attempt(sessionId = 3, day = 14, weight = null, reps = 12),
            )

            val progression = useCase(BENCH, PERIOD).first()

            assertEquals(3, progression.attempts.size)
            // 60 kg x (1 + 10/30) = 80 kg, and the other two project nothing.
            assertEquals(listOf(at(7)), progression.estimates.map(ExerciseEstimate::finishedAt))
            assertEquals(listOf(80_000_000L), progression.estimates.map(ExerciseEstimate::estimate))
        }

    @Test
    fun `the best of each kind is the newest row of that kind, and other exercises are not read`() =
        runTest {
            write(BENCH, PrType.MAX_WEIGHT, achievedAt = at(0), weight = kg(80))
            write(BENCH, PrType.MAX_WEIGHT, achievedAt = at(7), weight = kg(85))
            write(BENCH, PrType.MAX_VOLUME, achievedAt = at(0), weight = kg(1_200))
            write(SQUAT, PrType.MAX_WEIGHT, achievedAt = at(7), weight = kg(200))

            val records = useCase(BENCH, PERIOD).first().records

            assertEquals(setOf(PrType.MAX_WEIGHT, PrType.MAX_VOLUME), records.keys)
            assertEquals(kg(85), records.getValue(PrType.MAX_WEIGHT).weight)
            assertEquals(kg(1_200), records.getValue(PrType.MAX_VOLUME).weight)
        }

    @Test
    fun `a record set in the most recent attempt is new, and an older one is not`() =
        runTest {
            progressionRepository.setAttempts(
                BENCH,
                attempt(sessionId = 1, day = 0, weight = kg(80), reps = 5),
                attempt(sessionId = 2, day = 7, weight = kg(85), reps = 5),
            )
            // Broken during the second attempt, which opened an hour before it was closed out.
            write(BENCH, PrType.MAX_WEIGHT, achievedAt = at(7) - 1_800_000L, weight = kg(85))
            // Broken during the first, and still the best of its kind.
            write(BENCH, PrType.MAX_VOLUME, achievedAt = at(0), weight = kg(1_200))

            assertEquals(setOf(PrType.MAX_WEIGHT), useCase(BENCH, PERIOD).first().newRecords)
        }

    @Test
    fun `a record achieved on the attempt's first set counts as new`() =
        runTest {
            progressionRepository.setAttempts(
                BENCH,
                attempt(sessionId = 1, day = 0, weight = kg(85), reps = 5),
            )
            write(BENCH, PrType.MAX_WEIGHT, achievedAt = at(0) - SESSION_LENGTH, weight = kg(85))

            assertEquals(setOf(PrType.MAX_WEIGHT), useCase(BENCH, PERIOD).first().newRecords)
        }

    @Test
    fun `a window with no attempts badges nothing, and still reports the all-time bests`() =
        runTest {
            progressionRepository.setExerciseSetCount(BENCH, 142)
            write(BENCH, PrType.MAX_WEIGHT, achievedAt = at(0), weight = kg(85))

            val progression = useCase(BENCH, PERIOD).first()

            assertTrue(progression.isEmpty)
            assertFalse(progression.hasTrend)
            assertTrue(progression.newRecords.isEmpty())
            assertEquals(kg(85), progression.records.getValue(PrType.MAX_WEIGHT).weight)
            assertEquals(142, progression.totalSets)
        }

    @Test
    fun `one attempt is not a trend`() =
        runTest {
            progressionRepository.setAttempts(
                BENCH,
                attempt(sessionId = 1, day = 0, weight = kg(80), reps = 5),
            )

            val progression = useCase(BENCH, PERIOD).first()

            assertFalse(progression.isEmpty)
            assertFalse(progression.hasTrend)
        }

    @Test
    fun `an exercise with no history at all reports nothing rather than failing`() =
        runTest {
            val progression = useCase(BENCH, PERIOD).first()

            assertTrue(progression.attempts.isEmpty())
            assertTrue(progression.estimates.isEmpty())
            assertTrue(progression.records.isEmpty())
            assertEquals(0, progression.totalSets)
            assertNull(progression.records[PrType.ESTIMATED_1RM])
        }

    /** One attempt, with a top set unless the caller says otherwise. */
    private fun attempt(
        sessionId: Long,
        day: Long,
        sets: Int = 4,
        volume: Long = kg(1_000),
        weight: Long? = kg(80),
        reps: Int? = 5,
    ) = ExerciseAttempt(
        sessionId = sessionId,
        finishedAt = at(day),
        sets = sets,
        volume = volume,
        topSet = if (weight == null && reps == null) null else AttemptSet(weight = weight, reps = reps),
        firstSetAt = at(day) - SESSION_LENGTH,
    )

    /**
     * Writes a record through the store's own seam, so the rows this reads back are rows the
     * PR engine could actually have produced.
     */
    private suspend fun write(
        exerciseId: Long,
        type: PrType,
        achievedAt: Long,
        weight: Long,
    ) {
        recordRepository.insertIfBetter(
            exerciseId = exerciseId,
            type = type,
            record = PersonalRecord(
                exerciseId = exerciseId,
                workoutSetId = achievedAt,
                type = type,
                weight = weight,
                achievedAt = achievedAt,
            ),
            isBetter = { true },
        )
    }
}

/** How long a fixture's session runs for, from its first set to being closed out. */
private const val SESSION_LENGTH = 3_600_000L
