package com.titan.core.data.di

import com.titan.core.data.importer.WorkManagerHistoryImportRunner
import com.titan.core.domain.importer.HistoryImportRunner
import com.titan.core.domain.importer.HistoryParsers
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The import from another app's moving parts, beside its use case in
 * [HistoryImportUseCaseModule]: which parser reads each source, and what runs the import where
 * leaving the screen cannot cancel it.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HistoryImportRunnerModule {

    /**
     * `@Singleton` for the reason `AutoBackupScheduler`'s binding is: it holds the lazily
     * resolved `WorkManager`, and one instance is one place that happens.
     */
    @Binds
    @Singleton
    abstract fun bindHistoryImportRunner(impl: WorkManagerHistoryImportRunner): HistoryImportRunner

    companion object {

        /** Every source the screen offers, read by the parser written for it. Stateless. */
        @Provides
        fun provideHistoryParsers(): HistoryParsers = HistoryParsers.SUPPORTED
    }
}
