package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.PersonalRecordEntry
import com.opengym.core.domain.repository.PersonalRecordRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The most recent personal record the user has broken, with the figure it beat — Home's
 * recent-PR card, and the one row of the records list that is *news*.
 *
 * ### Why this is a use case and not a repository method
 *
 * Because it is already answerable. [PersonalRecordRepository.getRecordsPage] returns the
 * records newest first with the previous figure and the exercise name joined on, which is
 * exactly this row; asking for a page of one is the whole query. A dedicated method would be
 * a second SQL statement saying the same thing, kept in step by nothing, for a saving of one
 * row.
 *
 * ### One read per change, and no read at all before the first record
 *
 * [PersonalRecordRepository.observeRecordCount] is the invalidation signal a hand-rolled query
 * over that table has to be given — Room invalidates its own `PagingSource` and cannot
 * invalidate this. It is [distinctUntilChanged] first, so a table that re-emits without having
 * changed size does not re-run the page read; records are only ever inserted (and deleted with
 * the set behind them), so every change to the newest record moves the count.
 *
 * A count of zero short-circuits: a user who has never broken a record gets **null** without a
 * query, which is also what makes the empty case cheap on the screen that draws it on every
 * launch.
 */
class LatestPersonalRecordUseCase(private val personalRecordRepository: PersonalRecordRepository) {

    /** The newest record on file, or **null** for a user who has not broken one. */
    operator fun invoke(): Flow<PersonalRecordEntry?> =
        personalRecordRepository.observeRecordCount()
            .distinctUntilChanged()
            .map { count ->
                if (count <= 0) {
                    null
                } else {
                    personalRecordRepository
                        .getRecordsPage(type = null, offset = 0, limit = LATEST_ONLY)
                        .firstOrNull()
                }
            }

    private companion object {
        /** The newest record and nothing behind it — the card has room for one. */
        const val LATEST_ONLY = 1
    }
}
