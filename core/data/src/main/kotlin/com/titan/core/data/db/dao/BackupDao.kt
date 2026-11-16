package com.titan.core.data.db.dao

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Every whole-table read, insert and delete an export or an import needs, as one DAO.
 *
 * ### One `@Dao` over six interfaces
 *
 * Twenty tables times three operations is sixty methods, which is not a reviewable
 * interface and is well past what this project's lint budget admits in one. Splitting them into
 * six DAOs and hanging six accessors off [com.titan.core.data.db.TitanDatabase] would put
 * the split in the *database's* API, where it means nothing to a caller — and would take that
 * class past the same budget from the other end.
 *
 * So the split is by cohesion and the composition is here: [CatalogueBackupDao],
 * [TemplateBackupDao], [HistoryBackupDao], [ProgressionBackupDao], [GamificationBackupDao] and [GymBackupDao]
 * each cover one group of the schema and are plain interfaces, and this is the single `@Dao`
 * Room generates for. Room collects inherited abstract methods, so every `@Query` and `@Insert`
 * declared on a facet is implemented here.
 *
 * A caller wanting one group takes the facet as its type — `BackupRepositoryImpl` does not,
 * because it needs all six.
 */
@Dao
interface BackupDao :
    CatalogueBackupDao,
    TemplateBackupDao,
    HistoryBackupDao,
    ProgressionBackupDao,
    GamificationBackupDao,
    GymBackupDao {

    /**
     * The single integer [query] selects — a `COUNT(*)` over one table, for an export's total.
     *
     * Raw, because the table is the argument and SQL cannot bind an identifier; a `suspend` DAO
     * method rather than `RoomDatabase.query`, because an export counts inside the read
     * transaction it pages in, and only a DAO call is routed onto the thread holding it. The
     * raw `query` refuses to run from any other thread while a suspending transaction is open.
     */
    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Long
}
