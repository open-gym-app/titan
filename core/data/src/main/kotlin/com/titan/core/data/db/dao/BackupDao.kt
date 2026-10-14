package com.titan.core.data.db.dao

import androidx.room.Dao

/**
 * Every whole-table read, insert and delete an export or an import needs, as one DAO.
 *
 * ### One `@Dao` over five interfaces
 *
 * Nineteen tables times three operations is fifty-seven methods, which is not a reviewable
 * interface and is well past what this project's lint budget admits in one. Splitting them into
 * five DAOs and hanging five accessors off [com.titan.core.data.db.TitanDatabase] would put
 * the split in the *database's* API, where it means nothing to a caller — and would take that
 * class past the same budget from the other end.
 *
 * So the split is by cohesion and the composition is here: [CatalogueBackupDao],
 * [TemplateBackupDao], [HistoryBackupDao], [ProgressionBackupDao] and [GamificationBackupDao]
 * each cover one group of the schema and are plain interfaces, and this is the single `@Dao`
 * Room generates for. Room collects inherited abstract methods, so every `@Query` and `@Insert`
 * declared on a facet is implemented here.
 *
 * A caller wanting one group takes the facet as its type — `BackupRepositoryImpl` does not,
 * because it needs all five.
 */
@Dao
interface BackupDao :
    CatalogueBackupDao,
    TemplateBackupDao,
    HistoryBackupDao,
    ProgressionBackupDao,
    GamificationBackupDao,
    GymBackupDao,
    MarketplaceBackupDao
