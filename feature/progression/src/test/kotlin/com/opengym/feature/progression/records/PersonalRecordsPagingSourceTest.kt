package com.opengym.feature.progression.records

import androidx.paging.PagingSource
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.RecordTally
import com.opengym.core.domain.repository.PersonalRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The records list's own paging source.
 *
 * ### What is actually being asserted
 *
 * That the list **does not load the whole table** — #103's acceptance criterion — and that the key
 * it pages by survives the one thing that makes a page shorter than it asked for: a row the build
 * cannot read being dropped by the repository. A source that keyed off the returned count would
 * skip a record for every one dropped, silently, and no amount of scrolling would reveal it.
 */
class PersonalRecordsPagingSourceTest {

    @Test
    fun `the first page is a window, not the history`() =
        runTest {
            val store = FakeRecordPageStore(total = 500)
            val source = PersonalRecordsPagingSource(store, type = null)

            val page = source.load(refresh(loadSize = 30)) as PagingSource.LoadResult.Page

            assertEquals(30, page.data.size)
            // The whole criterion, stated as the question the store was asked rather than as the
            // answer it gave: a source that fetched everything and returned thirty would pass an
            // assertion on `page.data` alone.
            assertEquals(listOf(0 to 30), store.requested)
        }

    @Test
    fun `the next key advances by the window asked for, not by the rows returned`() =
        runTest {
            // One row in every page is unreadable to this build and is dropped by the repository.
            val store = FakeRecordPageStore(total = 500, dropPerPage = 1)
            val source = PersonalRecordsPagingSource(store, type = null)

            val page = source.load(refresh(loadSize = 30)) as PagingSource.LoadResult.Page

            assertEquals(29, page.data.size)
            // 30, not 29. Keying off what came back would leave row 29 unreachable — and it would be
            // a *different* row skipped on every page, which is the kind of loss nobody notices.
            assertEquals(30, page.nextKey)
        }

    @Test
    fun `an empty page ends the list`() =
        runTest {
            val store = FakeRecordPageStore(total = 10)
            val source = PersonalRecordsPagingSource(store, type = null)

            val page = source.load(append(key = 30, loadSize = 30)) as PagingSource.LoadResult.Page

            assertTrue(page.data.isEmpty())
            // The one unambiguous signal there is nothing further, as opposed to a short page.
            assertNull(page.nextKey)
        }

    @Test
    fun `the filter is passed to the store rather than applied to the page`() =
        runTest {
            val store = FakeRecordPageStore(total = 100)
            val source = PersonalRecordsPagingSource(store, type = PrType.MAX_REPS)

            source.load(refresh(loadSize = 30))

            assertEquals(listOf(PrType.MAX_REPS), store.requestedTypes)
        }

    @Test
    fun `a failing read is reported rather than thrown`() =
        runTest {
            val source = PersonalRecordsPagingSource(FailingRecordStore, type = null)

            val result = source.load(refresh(loadSize = 30))

            // Reported, so the rows already on screen stay and Paging can retry. An exception out of
            // `load` takes the whole list down for a query that may well succeed a second later.
            assertTrue(result is PagingSource.LoadResult.Error)
        }

    private fun refresh(loadSize: Int) =
        PagingSource.LoadParams.Refresh<Int>(
            key = null,
            loadSize = loadSize,
            placeholdersEnabled = false,
        )

    private fun append(
        key: Int,
        loadSize: Int,
    ) = PagingSource.LoadParams.Append(
        key = key,
        loadSize = loadSize,
        placeholdersEnabled = false,
    )
}

/**
 * A store holding [total] records, which records what it was asked for.
 *
 * @param dropPerPage how many rows a page silently loses — the repository's "drop what this build
 *   cannot read" behaviour, which is what makes a page shorter than its limit.
 */
private class FakeRecordPageStore(
    private val total: Int,
    private val dropPerPage: Int = 0,
) : PersonalRecordRepository by UnusedRecordStore {

    val requested = mutableListOf<Pair<Int, Int>>()
    val requestedTypes = mutableListOf<PrType?>()

    override suspend fun getRecordsPage(
        type: PrType?,
        offset: Int,
        limit: Int,
    ): List<PersonalRecordEntry> {
        requested += offset to limit
        requestedTypes += type

        val available = (total - offset).coerceAtLeast(0)
        val size = (minOf(available, limit) - dropPerPage).coerceAtLeast(0)
        return List(size) { index -> entry(id = (offset + index).toLong()) }
    }
}

private object FailingRecordStore : PersonalRecordRepository by UnusedRecordStore {
    override suspend fun getRecordsPage(
        type: PrType?,
        offset: Int,
        limit: Int,
    ): List<PersonalRecordEntry> = error("the store is unavailable")
}

private fun entry(id: Long) =
    PersonalRecordEntry(
        record = PersonalRecord(
            id = id,
            exerciseId = 1,
            workoutSetId = id,
            type = PrType.MAX_WEIGHT,
            weight = 90_000_000,
            achievedAt = id,
        ),
        exerciseName = "Bench press",
        previousValue = null,
        isRecent = false,
    )

/**
 * The reads this test never makes.
 *
 * Delegation rather than a hand-written stub of the whole interface: the source touches exactly
 * one method, and a fake that implemented the other six would have to be revisited every time the
 * contract grows for reasons that have nothing to do with paging.
 */
private object UnusedRecordStore : PersonalRecordRepository {
    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> = flowOf(emptyMap())
    override fun getRecordsForExercise(exerciseId: Long): Flow<List<PersonalRecord>> = flowOf(emptyList())
    override suspend fun count(): Int = 0
    override fun observeRecordCount(): Flow<Int> = flowOf(0)
    override fun getRecordTally(days: ClosedRange<LocalDate>): Flow<RecordTally> =
        flowOf(RecordTally(records = 0, exercises = 0))

    override suspend fun getRecordsPage(
        type: PrType?,
        offset: Int,
        limit: Int,
    ): List<PersonalRecordEntry> = emptyList()

    override suspend fun insertIfBetter(
        exerciseId: Long,
        type: PrType,
        record: PersonalRecord,
        isBetter: (current: PersonalRecord?) -> Boolean,
    ): PersonalRecord? = null
}
