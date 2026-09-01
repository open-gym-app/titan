package com.opengym.feature.progression.records

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.repository.PersonalRecordRepository

/**
 * The records list, one window at a time.
 *
 * ### Why this is hand-rolled rather than Room's
 *
 * Room can generate a `PagingSource` straight from a query, and `PersonalRecordDao` has one for
 * the per-exercise list. It cannot be used *here*, because the type is AndroidX and this screen
 * reaches its data through `:core:domain`, which carries no Android dependencies — see
 * ARCHITECTURE.md, and `PersonalRecordRepository.getRecordsPage`, which is the offset-and-limit
 * contract this is built over.
 *
 * The trade is deliberate and has one real cost, handled below: Room's generated source is told
 * when its table changes, and this one is not.
 *
 * ### Invalidation is the caller's job
 *
 * Nothing here observes the store. A record written while the list is open — the user finishes a
 * workout and comes back — leaves this source serving a snapshot that no longer matches the
 * table. The ViewModel watches `observeRecordCount()` and invalidates, which is why that read
 * exists at all. A source that polled instead would be a query per interval for a screen that
 * changes a few times a week.
 *
 * ### The key is the offset, not the row count
 *
 * `nextKey` advances by the size *asked for* rather than by the size returned. A page can come
 * back short without being the last one: the repository drops rows whose `pr_type` this build
 * cannot read, so a window of twenty can legitimately yield nineteen. Keying off the returned
 * count would skip a row for every one dropped, and a list that silently loses records is worse
 * than one that occasionally loads a page it did not need.
 *
 * The end of the list is an *empty* page, which is the one unambiguous signal.
 *
 * @param type which kind to list, or null for all. A new instance is created when it changes —
 *   see [PersonalRecordsViewModel] — because a filter change is a different list rather than a
 *   different position in this one.
 */
class PersonalRecordsPagingSource(
    private val repository: PersonalRecordRepository,
    private val type: PrType?,
) : PagingSource<Int, PersonalRecordEntry>() {

    // Paging's own contract is that `load` reports failure as `LoadResult.Error(Throwable)`
    // rather than throwing, so the catch has to be as wide as what a store can fail with — a
    // narrower one would let the exception it did not name take the whole screen down.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, PersonalRecordEntry> =
        try {
            val offset = params.key ?: 0
            val entries = repository.getRecordsPage(
                type = type,
                offset = offset,
                limit = params.loadSize,
            )

            LoadResult.Page(
                data = entries,
                // No previous key: the list only ever grows downward from the newest record, and
                // a `prevKey` would let Paging load *backwards* past the top of a list that has
                // no rows above its first.
                prevKey = null,
                nextKey = if (entries.isEmpty()) null else offset + params.loadSize,
            )
        } catch (failure: Exception) {
            // Reported rather than thrown: a failed page leaves the rows already on screen and
            // lets Paging retry, where an exception out of `load` would take the whole list down
            // for a query that may well succeed a second later.
            LoadResult.Error(failure)
        }

    /**
     * Where to resume after an invalidation — the anchor's own page, so the rows on screen stay
     * on screen.
     *
     * Falling back to the start rather than to the anchor position when there is no page to
     * anchor to: refreshing at an offset the list may no longer be long enough for would open on
     * a blank list the user has to scroll up out of.
     */
    override fun getRefreshKey(state: PagingState<Int, PersonalRecordEntry>): Int? =
        state.anchorPosition
            ?.let(state::closestPageToPosition)
            ?.let { page -> page.prevKey ?: page.nextKey?.minus(state.config.pageSize) }
}
