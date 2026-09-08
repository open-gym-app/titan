package com.opengym.feature.progression.records

import androidx.lifecycle.SavedStateHandle
import androidx.paging.testing.asSnapshot
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.RecordTally
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.feature.progression.afterProcessDeath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * The records list's ViewModel — the seam the other two tests leave uncovered.
 *
 * `PersonalRecordsPageDaoTest` proves the query returns rows and
 * `PersonalRecordsPagingSourceTest` proves the source pages through them. Neither runs the
 * *wiring*, which is what this covers: that the pager the screen collects actually serves what the
 * store holds, that the filter reaches the query, and that an empty store reads as "no records"
 * rather than as still loading.
 *
 * ### What it does not cover, and why that is worth writing down
 *
 * A bug shipped past all three of these tests: the count is observed to invalidate the pager, and
 * invalidating on its *first* emission cancelled the load the pager had just started, so the
 * device showed a header reading "4 records" beside an empty list. `drop(1)` is the fix.
 *
 * These tests do **not** reproduce it. Reintroducing the defect leaves them green, because the
 * unconfined test dispatcher settles the invalidation and the load in an order the device does not
 * guarantee — the failure is a race, and a scheduler that removes the race removes the failure
 * with it. The `uiState` collection below is kept because it is what the screen does and what
 * starts the invalidating flow, but it should not be read as a guard against that bug returning.
 * Catching it needs a test that can order the two, or the device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonalRecordsViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the list serves the records the store holds`() =
        runTest {
            val viewModel = PersonalRecordsViewModel(SavedStateHandle(), FakeRecordStore(entries(4)))
            collectStateInBackground(viewModel)

            val snapshot = viewModel.records.asSnapshot()

            assertEquals(4, snapshot.size)
        }

    @Test
    fun `the header counts what the store holds, whatever is listed`() =
        runTest {
            val store = FakeRecordStore(entries(4))
            val viewModel = PersonalRecordsViewModel(SavedStateHandle(), store)
            collectStateInBackground(viewModel)

            assertEquals(4, viewModel.uiState.value.totalRecords)
            assertEquals(false, viewModel.uiState.value.isLoading)
        }

    @Test
    fun `choosing a filter asks the store for that kind`() =
        runTest {
            val store = FakeRecordStore(entries(4))
            val viewModel = PersonalRecordsViewModel(SavedStateHandle(), store)
            collectStateInBackground(viewModel)

            viewModel.onEvent(PersonalRecordsEvent.FilterSelected(PrType.MAX_REPS))
            viewModel.records.asSnapshot()

            assertEquals(PrType.MAX_REPS, viewModel.uiState.value.filter)
            // The filter reaches the query rather than being applied to a page already fetched.
            assertEquals(true, store.requestedTypes.contains(PrType.MAX_REPS))
        }

    @Test
    fun `a store with nothing in it reports no records rather than still loading`() =
        runTest {
            val viewModel = PersonalRecordsViewModel(SavedStateHandle(), FakeRecordStore(emptyList()))
            collectStateInBackground(viewModel)

            assertEquals(true, viewModel.uiState.value.hasNoRecords)
        }

    /**
     * Collects `uiState` the way the screen does.
     *
     * `stateIn(WhileSubscribed)` starts nothing until something subscribes, and the invalidating
     * collector rides on that — so without this the tests would exercise a ViewModel in a state
     * the screen never puts it in.
     */
    private fun TestScope.collectStateInBackground(viewModel: PersonalRecordsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
    }

    // ─── Process death (#183) ───────────────────────────────────────────────────────────

    @Test
    fun `the kind the list is filtered to comes back after process death`() =
        runTest {
            val handle = SavedStateHandle()
            val before = PersonalRecordsViewModel(handle, FakeRecordStore(entries(4)))
            collectStateInBackground(before)
            before.onEvent(PersonalRecordsEvent.FilterSelected(PrType.MAX_WEIGHT))

            val restored = PersonalRecordsViewModel(handle.afterProcessDeath(), FakeRecordStore(entries(4)))
            collectStateInBackground(restored)

            assertEquals(PrType.MAX_WEIGHT, restored.uiState.value.filter)
        }

    private fun entries(count: Int) =
        List(count) { index ->
            PersonalRecordEntry(
                record = PersonalRecord(
                    id = index.toLong(),
                    exerciseId = 1,
                    workoutSetId = index.toLong(),
                    type = PrType.MAX_WEIGHT,
                    weight = 90_000_000,
                    achievedAt = index.toLong(),
                ),
                exerciseName = "Bench press",
                previousValue = null,
                isRecent = false,
            )
        }
}

/** A store holding a fixed list, which records the filters it was asked for. */
private class FakeRecordStore(private val all: List<PersonalRecordEntry>) : PersonalRecordRepository {

    val requestedTypes = mutableListOf<PrType?>()
    private val count = MutableStateFlow(all.size)

    override suspend fun getRecordsPage(
        type: PrType?,
        offset: Int,
        limit: Int,
    ): List<PersonalRecordEntry> {
        requestedTypes += type
        return all.drop(offset).take(limit)
    }

    override fun observeRecordCount(): Flow<Int> = count

    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> = flowOf(emptyMap())

    override fun getRecordsForExercise(exerciseId: Long): Flow<List<PersonalRecord>> = flowOf(emptyList())

    override suspend fun count(): Int = all.size

    override fun getRecordTally(days: ClosedRange<LocalDate>): Flow<RecordTally> =
        flowOf(RecordTally(records = 0, exercises = 0))

    override suspend fun insertIfBetter(
        exerciseId: Long,
        type: PrType,
        record: PersonalRecord,
        isBetter: (current: PersonalRecord?) -> Boolean,
    ): PersonalRecord? = null
}
