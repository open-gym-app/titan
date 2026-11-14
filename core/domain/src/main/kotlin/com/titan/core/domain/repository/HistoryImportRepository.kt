package com.titan.core.domain.repository

/**
 * The two things an import from another app needs of the store that no other repository offers.
 *
 * Everything else the import writes goes through the repository that owns it — exercises through
 * [ExerciseRepository], sessions through [SessionRepository], the replay through the completion
 * pipeline's own — so that an imported row is written by exactly the code a logged one is. What
 * those cannot give it is the import taken *as a whole*, which is what this is for.
 */
interface HistoryImportRepository {

    /**
     * Runs [block] as one transaction over the **whole** store: every write any repository makes
     * from inside it lands, or none does.
     *
     * Wider than [SessionRepository.atomically], which promises only its own calls, and that is
     * the point. An import creates exercises, templates, measurements and sessions, and the
     * replay writes records, XP, the streak, unlocks, quest completions and drops; a failure on
     * the four-hundredth workout must leave none of the first three hundred and ninety-nine
     * behind, or the user is left with half a history and a retry that skips it as duplicates.
     *
     * The same caution [SessionRepository.atomically] gives applies with more force: every other
     * write in the app waits for [block], so it must hold nothing but database work, and must not
     * wait on anything that writes to the database itself.
     */
    suspend fun <T> atomically(block: suspend () -> T): T

    /**
     * Deletes every personal record set by a session that finished at or after [finishedAt],
     * returning how many went.
     *
     * Records are derived data — PERSISTENCE.md says so — and this is the one caller that needs
     * to derive some of them again. Detection compares a session against the records already on
     * file, so it is only right when sessions are detected in the order they happened. An
     * imported workout older than sessions already on the device would otherwise be judged
     * against records set *after* it, and a record from 2023 written below one from 2025 would
     * become the "latest" of its kind. The import therefore forgets the records from its earliest
     * workout on and replays every session from there, imported and existing alike, in order.
     *
     * Only finished sessions are matched: a session in progress has not been through detection,
     * so it holds no records to forget.
     */
    suspend fun forgetRecordsOfSessionsFinishedFrom(finishedAt: Long): Int
}
