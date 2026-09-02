package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.Quest
import com.opengym.core.domain.model.QuestMetric
import com.opengym.core.domain.model.QuestPeriod
import com.opengym.core.domain.model.QuestProgress
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Month
import java.util.TimeZone

/**
 * The board: which figures each metric reads, over which range, and where completion comes from.
 */
class QuestProgressUseCaseTest {

    // ─── The metrics ────────────────────────────────────────────────────────────────────

    @Test
    fun `sessions count the workouts finished in the period`() =
        runTest {
            val rig = Rig(catalogue = listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 2)

            assertEquals(2, rig.board().single().progress)
        }

    @Test
    fun `two sessions on one day count twice`() =
        runTest {
            // The one place this differs from the consistency figure, which counts training *days*.
            // A quest to train three times is met by three workouts however they fall.
            val rig = Rig(catalogue = listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 2)
            rig.progression.setSessionsOn(WEDNESDAY, 1)

            assertEquals(3, rig.board().single().progress)
            assertTrue(rig.board().single().quest.isMetBy(rig.board().single().progress))
        }

    @Test
    fun `records count every record broken in the period`() =
        runTest {
            val rig = Rig(catalogue = listOf(ONE_RECORD))
            rig.records.seedRecord(WEDNESDAY)
            rig.records.seedRecord(WEDNESDAY)

            assertEquals(2, rig.board().single().progress)
        }

    @Test
    fun `volume is reported in kilograms rather than the milligrams it is stored in`() =
        runTest {
            val rig = Rig(catalogue = listOf(TWENTY_TONNES))
            // 20,000 kg, in the milligram-reps the store actually sums.
            rig.progression.setVolumeOn(WEDNESDAY, 20_000L * 1_000_000L)

            assertEquals(20_000, rig.board().single().progress)
        }

    @Test
    fun `a part of a kilogram does not finish a quest early`() =
        runTest {
            // Rounded down: 19,999.6 kg is not the 20,000 the quest asked for.
            val rig = Rig(catalogue = listOf(TWENTY_TONNES))
            rig.progression.setVolumeOn(WEDNESDAY, 20_000L * 1_000_000L - 400_000L)

            val row = rig.board().single()
            assertEquals(19_999, row.progress)
            assertFalse(row.quest.isMetBy(row.progress))
        }

    @Test
    fun `a quest reads its own metric and no other`() =
        runTest {
            val rig = Rig(catalogue = listOf(ONE_RECORD))
            rig.progression.setSessionsOn(WEDNESDAY, 40)
            rig.progression.setVolumeOn(WEDNESDAY, 900_000L * 1_000_000L)

            assertEquals(0, rig.board().single().progress)
        }

    // ─── The range ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a weekly quest ignores training from the week before`() =
        runTest {
            val rig = Rig(catalogue = listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY.minusWeeks(1), 5)
            rig.progression.setSessionsOn(WEDNESDAY, 1)

            assertEquals(1, rig.board().single().progress)
        }

    @Test
    fun `a weekly quest counts its own Monday and its own Sunday`() =
        runTest {
            val rig = Rig(catalogue = listOf(THREE_SESSIONS))
            val week = QuestPeriod.WEEKLY.rangeOf(WEDNESDAY)
            rig.progression.setSessionsOn(week.start, 1)
            rig.progression.setSessionsOn(week.endInclusive, 1)
            // Just outside, on both sides.
            rig.progression.setSessionsOn(week.start.minusDays(1), 9)
            rig.progression.setSessionsOn(week.endInclusive.plusDays(1), 9)

            assertEquals(2, rig.board().single().progress)
        }

    @Test
    fun `a monthly quest counts the whole month even though it is not whole weeks`() =
        runTest {
            // The case the scalar reads exist for. September 2025 opens on a Monday and closes on
            // a Tuesday, so its last week is a partial bucket in the Monday-grouped series — and
            // the 30th must still count, while the 1st of October must not.
            val rig = Rig(catalogue = listOf(TWELVE_SESSIONS), today = LocalDate.of(2025, Month.SEPTEMBER, 17))
            rig.progression.setSessionsOn(LocalDate.of(2025, Month.SEPTEMBER, 1), 1)
            rig.progression.setSessionsOn(LocalDate.of(2025, Month.SEPTEMBER, 30), 1)
            rig.progression.setSessionsOn(LocalDate.of(2025, Month.OCTOBER, 1), 9)
            rig.progression.setSessionsOn(LocalDate.of(2025, Month.AUGUST, 31), 9)

            assertEquals(2, rig.board().single().progress)
        }

    @Test
    fun `the range asked for is the period, not a snapped set of weeks`() =
        runTest {
            val rig = Rig(catalogue = listOf(TWELVE_SESSIONS), today = LocalDate.of(2025, Month.SEPTEMBER, 17))
            rig.board()

            assertTrue(
                "the month was not read over its own bounds: ${rig.progression.requestedRanges}",
                rig.progression.requestedRanges.any {
                    it.start == LocalDate.of(2025, Month.SEPTEMBER, 1) &&
                        it.endInclusive == LocalDate.of(2025, Month.SEPTEMBER, 30)
                },
            )
        }

    @Test
    fun `quests of one period share their reads`() =
        runTest {
            // Per period, not per quest: four weekly quests must not be four times the queries.
            val rig = Rig(catalogue = listOf(THREE_SESSIONS, FIVE_SESSIONS, TWENTY_TONNES))
            rig.board()

            // Two reads of `progression` for the one weekly period — the session count and the
            // volume — however many quests read them.
            assertEquals(2, rig.progression.requestedRanges.size)
        }

    // ─── The boundary does not move with the device ─────────────────────────────────────

    @Test
    fun `the period is the same whatever time zone the device is in`() =
        runTest {
            // #105 asks for local midnight; this cuts in UTC, like `TrainingWeek` and
            // `StreakCalculator`. The observable consequence is here: a lifter who flies from Tokyo
            // to Los Angeles sees the same week, because the boundary is computed from an instant
            // rather than from the phone's calendar. That also makes the issue's daylight-saving
            // case moot — UTC has no transition to get wrong.
            val original = TimeZone.getDefault()
            try {
                val seen = listOf("Asia/Tokyo", "America/Los_Angeles", "UTC", "Pacific/Kiritimati").map { zone ->
                    TimeZone.setDefault(TimeZone.getTimeZone(zone))
                    // Late on a Sunday UTC: the hour where a local-midnight rule would disagree.
                    val rig = Rig(catalogue = listOf(THREE_SESSIONS), nowMillis = SUNDAY_LATE_UTC)
                    rig.board().single().periodStart
                }

                assertEquals("the week moved with the device's zone", 1, seen.toSet().size)
                assertEquals(LocalDate.of(2025, Month.SEPTEMBER, 1), seen.first())
            } finally {
                TimeZone.setDefault(original)
            }
        }

    // ─── Completion is read, never derived ──────────────────────────────────────────────

    @Test
    fun `a quest already banked stays complete after the training behind it is deleted`() =
        runTest {
            // The property [QuestProgress] exists to protect: the XP was paid, and this app does
            // not take XP back. A board that re-derived completion from the bar would un-complete
            // the quest and let the next session pay for it twice.
            val rig = Rig(catalogue = listOf(THREE_SESSIONS))
            rig.quests.seedCompleted(THREE_SESSIONS.id, QuestPeriod.WEEKLY.startOf(WEDNESDAY))
            rig.progression.setSessionsOn(WEDNESDAY, 1)

            val row = rig.board().single()
            assertEquals(1, row.progress)
            assertTrue(row.isComplete)
            assertEquals(1f, row.fraction, 0f)
        }

    @Test
    fun `meeting the target does not by itself make a quest complete`() =
        runTest {
            // Completion is a written row. Until the pass runs, the target being met is only a
            // figure — which is what keeps "is it complete" and "should it be completed" separate.
            val rig = Rig(catalogue = listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 5)

            assertFalse(rig.board().single().isComplete)
        }

    @Test
    fun `a completion in another period does not complete this one`() =
        runTest {
            val rig = Rig(catalogue = listOf(THREE_SESSIONS))
            rig.quests.seedCompleted(THREE_SESSIONS.id, QuestPeriod.WEEKLY.startOf(WEDNESDAY.minusWeeks(1)))

            assertFalse(rig.board().single().isComplete)
        }

    // ─── The board itself ───────────────────────────────────────────────────────────────

    @Test
    fun `the board comes back in catalogue order across both cadences`() =
        runTest {
            val catalogue = listOf(THREE_SESSIONS, FIVE_SESSIONS, TWELVE_SESSIONS, ONE_RECORD)
            val rig = Rig(catalogue = catalogue)

            assertEquals(catalogue, rig.board().map(QuestProgress::quest))
        }

    @Test
    fun `an empty catalogue is an empty board rather than one that never loads`() =
        runTest {
            // `combine` over no flows never emits; a board wired that way would spin forever.
            assertEquals(emptyList<QuestProgress>(), Rig(catalogue = emptyList()).board())
        }

    @Test
    fun `a quest overshot fills its bar rather than overflowing it`() =
        runTest {
            val rig = Rig(catalogue = listOf(THREE_SESSIONS))
            rig.progression.setSessionsOn(WEDNESDAY, 9)

            val row = rig.board().single()
            assertEquals(9, row.progress)
            assertEquals(1f, row.fraction, 0f)
        }

    private class Rig(
        val catalogue: List<Quest>,
        today: LocalDate = WEDNESDAY,
        nowMillis: Long = today.toEpochDay() * MILLIS_PER_DAY,
    ) {

        val progression = FakeProgressionRepository()
        val records = FakePersonalRecordRepository()
        val quests = FakeQuestRepository()

        private val useCase = QuestProgressUseCase(
            progressionRepository = progression,
            personalRecordRepository = records,
            questRepository = quests,
            clock = Clock { nowMillis },
            catalogue = catalogue,
        )

        suspend fun board(): List<QuestProgress> = useCase().first()
    }

    private companion object {

        /** Wednesday 3 September 2025, in the week of Monday the 1st. */
        val WEDNESDAY: LocalDate = LocalDate.of(2025, Month.SEPTEMBER, 3)

        /** 23:30 UTC on Sunday 7 September 2025 — the last half-hour of that week. */
        val SUNDAY_LATE_UTC: Long =
            LocalDate.of(2025, Month.SEPTEMBER, 7).toEpochDay() * MILLIS_PER_DAY + 23 * 3_600_000L + 1_800_000L

        const val MILLIS_PER_DAY = 86_400_000L

        val THREE_SESSIONS = quest("three_sessions", QuestMetric.SESSIONS_FINISHED, 3, QuestPeriod.WEEKLY)
        val FIVE_SESSIONS = quest("five_sessions", QuestMetric.SESSIONS_FINISHED, 5, QuestPeriod.WEEKLY)
        val ONE_RECORD = quest("one_record", QuestMetric.RECORDS_BROKEN, 1, QuestPeriod.WEEKLY)
        val TWENTY_TONNES = quest("twenty_tonnes", QuestMetric.VOLUME_KILOGRAMS, 20_000, QuestPeriod.WEEKLY)
        val TWELVE_SESSIONS = quest("twelve_sessions", QuestMetric.SESSIONS_FINISHED, 12, QuestPeriod.MONTHLY)

        /**
         * Quests built here rather than taken from [com.opengym.core.domain.gamification.Quests],
         * so a tuning pass on the shipped catalogue does not rewrite what these tests assert.
         * `QuestsTest` is what holds the real one to its shape.
         */
        fun quest(
            id: String,
            metric: QuestMetric,
            target: Int,
            period: QuestPeriod,
        ) = Quest(id = id, metric = metric, target = target, period = period, xpReward = 10)
    }
}

/** Seeds one record achieved on [day], which is all these tests read of it. */
private fun FakePersonalRecordRepository.seedRecord(day: LocalDate) {
    seed(
        PersonalRecord(
            id = 0L,
            exerciseId = 1L,
            workoutSetId = 1L,
            type = PrType.MAX_WEIGHT,
            achievedAt = day.toEpochDay() * 86_400_000L,
            weight = 100_000_000L,
        ),
    )
}
