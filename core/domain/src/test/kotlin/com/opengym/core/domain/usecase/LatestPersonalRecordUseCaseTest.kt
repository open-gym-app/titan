package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the newest record reads as, and what an empty table answers.
 *
 * The use case is two reads joined — a count to watch, and a page of one — so what is worth
 * pinning is that it takes the *newest* row rather than the first written, that it carries the
 * figure that row beat, and that it says null rather than querying when there is nothing on
 * file.
 */
class LatestPersonalRecordUseCaseTest {

    private val records = FakePersonalRecordRepository()
    private val latest = LatestPersonalRecordUseCase(records)

    @Test
    fun `no records at all is null rather than an empty row`() =
        runTest {
            assertNull(latest().first())
        }

    @Test
    fun `the newest record is the one answered with`() =
        runTest {
            records.seed(record(weight = 135_000_000, achievedAt = EARLIER))
            records.seed(record(weight = 142_000_000, achievedAt = LATER))

            val entry = requireNotNull(latest().first())

            assertEquals(142_000_000L, entry.record.weight)
        }

    @Test
    fun `the record it beat travels with it`() =
        runTest {
            // The whole reason the card can say "+7 kg": the improvement is a fact about two
            // rows, and asking for the second one separately would be a query per card.
            records.seed(record(weight = 135_000_000, achievedAt = EARLIER))
            records.seed(record(weight = 142_000_000, achievedAt = LATER))

            val entry = requireNotNull(latest().first())

            assertEquals(135_000_000L, entry.previousValue)
            assertEquals(7_000_000L, entry.improvement)
        }

    @Test
    fun `a first record of its kind beat nothing`() =
        runTest {
            // Null rather than zero: drawing "+0 kg" would credit the user with beating a
            // record they never held — see PersonalRecordEntry.improvement.
            records.seed(record(weight = 135_000_000, achievedAt = EARLIER))

            val entry = requireNotNull(latest().first())

            assertNull(entry.previousValue)
            assertNull(entry.improvement)
        }

    @Test
    fun `the newest record is the newest across every kind, not within one`() =
        runTest {
            // The card is "the last thing you beat", and the last thing beaten is whatever the
            // most recent session wrote — a rep record counts as much as a heaviest lift.
            records.seed(record(weight = 142_000_000, achievedAt = EARLIER))
            records.seed(
                PersonalRecord(
                    exerciseId = 1L,
                    workoutSetId = 2L,
                    type = PrType.MAX_REPS,
                    reps = 14,
                    achievedAt = LATER,
                ),
            )

            val entry = requireNotNull(latest().first())

            assertEquals(PrType.MAX_REPS, entry.record.type)
        }

    private fun record(
        weight: Long,
        achievedAt: Long,
    ) = PersonalRecord(
        exerciseId = 1L,
        workoutSetId = achievedAt,
        type = PrType.MAX_WEIGHT,
        weight = weight,
        achievedAt = achievedAt,
    )

    private companion object {
        /** Two moments, a day apart. Only their order matters. */
        const val EARLIER = 1_777_291_200_000L
        const val LATER = 1_777_377_600_000L
    }
}
