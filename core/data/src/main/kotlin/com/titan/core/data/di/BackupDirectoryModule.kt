package com.titan.core.data.di

import com.titan.core.data.backup.AppBackupDirectory
import com.titan.core.domain.backup.BackupDirectory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the one place that decides where backup files live (#259).
 *
 * `@Singleton` is load-bearing: [AppBackupDirectory] moves backups an older install left under
 * the other root once per instance, and every caller — the weekly backup, "Delete all data" and
 * the backup screen — must see the directory only after that move.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupDirectoryModule {

    @Binds
    @Singleton
    abstract fun bindBackupDirectory(impl: AppBackupDirectory): BackupDirectory
}
