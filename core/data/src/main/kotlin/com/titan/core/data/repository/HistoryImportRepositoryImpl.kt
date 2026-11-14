package com.titan.core.data.repository

import androidx.room.withTransaction
import com.titan.core.data.db.TitanDatabase
import com.titan.core.domain.repository.HistoryImportRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [HistoryImportRepository] over the one Room database every other repository also writes to.
 *
 * [atomically] is a plain [withTransaction], and that is why it spans the whole store: Room binds
 * the transaction to the coroutine, so every DAO call made from inside [atomically]'s block —
 * whichever repository makes it — joins the same transaction, and a repository method that opens
 * a transaction of its own (`ExerciseRepository.insert` with its links, a template with its body)
 * nests inside it rather than committing early.
 */
@Singleton
class HistoryImportRepositoryImpl @Inject constructor(private val database: TitanDatabase) : HistoryImportRepository {

    override suspend fun <T> atomically(block: suspend () -> T): T = database.withTransaction { block() }

    override suspend fun forgetRecordsOfSessionsFinishedFrom(finishedAt: Long): Int =
        database.personalRecordDao().deleteForSessionsFinishedFrom(finishedAt)
}
