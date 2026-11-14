package com.titan.core.data.di

import com.titan.core.data.backup.AppBackupDirectory
import com.titan.core.domain.backup.BackupDirectory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the one place that decides where backup files live (#259).
 *
 * Unscoped, because [AppBackupDirectory] carries `@Singleton` itself, and that annotation is
 * load-bearing: the class moves backups an older install left under the other root once per
 * instance, and every caller — the weekly backup, "Delete all data" and the backup screen —
 * must share the instance that did it. Scoping the class rather than this binding keeps it a
 * singleton for anyone who injects it directly.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupDirectoryModule {

    @Binds
    abstract fun bindBackupDirectory(impl: AppBackupDirectory): BackupDirectory
}
