package com.titan.feature.progression.records

import androidx.compose.runtime.Immutable
import com.titan.core.domain.model.PrType

/**
 * What the records list draws, apart from the rows themselves.
 *
 * ### The rows are not in here
 *
 * They arrive as a `Flow<PagingData<PersonalRecordEntry>>` collected by the screen, which is the
 * one thing on this screen that cannot be a plain value: `PagingData` is a stream of loads, not a
 * list, and putting it in an `@Immutable` state class would make a state object that is neither.
 * So the state carries what is *around* the list — the filter, and how many records exist — and
 * the list is passed alongside it. See [PersonalRecordsViewModel].
 *
 * @property filter the kind being listed, or **null** for all of them. Null rather than a set of
 *   all six, so "no filter" and "everything selected" are one state rather than two that render
 *   identically and compare unequal.
 * @property totalRecords how many records the user holds, across every kind — the figure the
 *   header shows, and unaffected by [filter], which narrows what is listed rather than what
 *   exists.
 * @property isLoading the count has not come back yet. Distinct from "no records": a blank screen
 *   and a screen that says the user has never set a record are different claims, and only one of
 *   them is safe to make before the store has answered.
 */
@Immutable
data class PersonalRecordsUiState(
    val filter: PrType? = null,
    val totalRecords: Int = 0,
    val isLoading: Boolean = true,
) {

    /**
     * Whether the user has never set a record.
     *
     * Asked of the total rather than of the paged list, and deliberately: the list is empty
     * whenever a *filter* matches nothing, which is a different screen — "no distance records
     * yet" is a reason to change the filter, "no records yet" is a reason to go and train. The
     * screen draws the empty state from this and the narrower one from the list itself.
     */
    val hasNoRecords: Boolean get() = !isLoading && totalRecords == 0
}
