package com.opengym.feature.progression.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.repository.PersonalRecordRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Every personal record, newest first, filtered by kind.
 *
 * ### The list is paged, and the filter restarts it
 *
 * A `Pager` is built per filter rather than one being filtered in memory: the filter is part of
 * the query, so changing it is a different list and not a different view of this one. `flatMapLatest`
 * is what discards the old pager when the chip changes — without it, a rapid tap through the chips
 * would leave several pagers loading into the same screen.
 *
 * `cachedIn` keeps the loaded pages across configuration changes. Without it a rotation re-queries
 * from the top and loses the user's scroll position, which on a list of a thousand records is the
 * difference between an app and a demo.
 *
 * ### Invalidation is explicit, because it has to be
 *
 * `PersonalRecordsPagingSource` reads through `:core:domain` and therefore is not Room's, so
 * nothing tells it the table changed. The count is observed for exactly that: when it moves — a
 * workout finished while this screen sat in the back stack — the current source is invalidated and
 * Paging reloads. See `PersonalRecordRepository.observeRecordCount`.
 *
 * The count is also what the header shows, so the read is not overhead spent purely on
 * correctness.
 */
@HiltViewModel
class PersonalRecordsViewModel
@Inject
constructor(private val personalRecordRepository: PersonalRecordRepository) :
    ViewModel() {

    private val filter = MutableStateFlow<PrType?>(null)

    /** The live source, held so a change in the store can invalidate it. */
    private var currentSource: PersonalRecordsPagingSource? = null

    /**
     * How many records exist — the header's figure, and the signal that the list is stale.
     *
     * `distinctUntilChanged` because Room re-emits on any write to the table, and only a change
     * in the count is news to either reader.
     */
    private val recordCount: StateFlow<Int?> =
        personalRecordRepository.observeRecordCount()
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = null,
            )

    init {
        viewModelScope.launch {
            // `drop(1)`, and this is the whole point of the separate collector: the first
            // emission is not a *change*, it is the initial answer — and invalidating on it
            // cancels the load the pager has just started, leaving the screen with an empty list
            // beside a header that correctly says there are records. Only a later movement in the
            // count means the table shifted under a source that is already serving rows.
            recordCount.filterNotNull().drop(1).collect { currentSource?.invalidate() }
        }
    }

    val uiState: StateFlow<PersonalRecordsUiState> =
        combine(filter, recordCount) { type, count ->
            PersonalRecordsUiState(
                filter = type,
                totalRecords = count ?: 0,
                isLoading = count == null,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = PersonalRecordsUiState(),
        )

    val records: Flow<PagingData<PersonalRecordEntry>> =
        filter
            .flatMapLatest { type ->
                Pager(
                    config = PagingConfig(
                        pageSize = PAGE_SIZE,
                        // Never load the whole table to satisfy the first screen: the criterion
                        // #103 asks for is that history is not loaded at once, and this is the
                        // knob that decides it.
                        enablePlaceholders = false,
                        initialLoadSize = PAGE_SIZE * 2,
                    ),
                    pagingSourceFactory = {
                        PersonalRecordsPagingSource(personalRecordRepository, type)
                            .also { currentSource = it }
                    },
                ).flow
            }
            .cachedIn(viewModelScope)

    fun onEvent(event: PersonalRecordsEvent) {
        when (event) {
            is PersonalRecordsEvent.FilterSelected -> filter.update { event.type }
        }
    }

    private companion object {

        /**
         * How many records a page holds.
         *
         * Comfortably more than a screenful, so scrolling does not stall on a query, and far less
         * than a heavy user's history — which is the whole point of paging this list at all.
         */
        const val PAGE_SIZE = 30

        /** Matches the other progression view models: long enough to survive a rotation. */
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
