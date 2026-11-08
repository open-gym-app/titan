package com.titan.core.data.di

import com.titan.core.domain.backup.AutoBackupScheduler
import com.titan.core.domain.repository.AutoBackupRepository
import com.titan.core.domain.repository.BackupRepository
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.repository.TemplateDraftRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.ExportUseCase
import com.titan.core.domain.usecase.ImportUseCase
import com.titan.core.domain.usecase.SetAutoBackupEnabledUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Export and import, composed over the repositories they need.
 *
 * Unscoped, like every other use-case module here: both are stateless compositions over
 * already-scoped repositories, per ARCHITECTURE.md's table.
 */
@Module
@InstallIn(SingletonComponent::class)
object BackupUseCaseModule {

    /**
     * The clock is the graph's, like every other use case that stamps something: a test that pins
     * the graph's clock gets a document dated at the moment it pinned. The page size is left at
     * its default, a memory/round-trip trade with no per-build answer; a test constructs this
     * directly to pin it small.
     */
    @Provides
    fun provideExportUseCase(
        backupRepository: BackupRepository,
        userPreferencesRepository: UserPreferencesRepository,
        clock: Clock,
    ): ExportUseCase =
        ExportUseCase(
            backupRepository = backupRepository,
            userPreferencesRepository = userPreferencesRepository,
            clock = clock,
        )

    /**
     * Takes the three stores an import has to *forget* as well as the two it writes. The live
     * session state and the two drafts all name row ids from before the wipe, so leaving them
     * would point a screen at a session or a template that no longer exists — see
     * [ImportUseCase] for the order and why it is that order.
     */
    @Provides
    fun provideImportUseCase(
        backupRepository: BackupRepository,
        userPreferencesRepository: UserPreferencesRepository,
        inProgressSessionRepository: InProgressSessionRepository,
        templateDraftRepository: TemplateDraftRepository,
        sessionStateRepository: SessionStateRepository,
    ): ImportUseCase =
        ImportUseCase(
            backupRepository = backupRepository,
            userPreferencesRepository = userPreferencesRepository,
            inProgressSessionRepository = inProgressSessionRepository,
            templateDraftRepository = templateDraftRepository,
            sessionStateRepository = sessionStateRepository,
        )

    /**
     * The auto-backup toggle (#181): the stored flag and the scheduled work, written together.
     *
     * The scheduler resolves to the WorkManager-backed implementation in this module's
     * `AutoBackupModule` sibling; nothing outside `:core:data` names WorkManager.
     */
    @Provides
    fun provideSetAutoBackupEnabledUseCase(
        autoBackupRepository: AutoBackupRepository,
        scheduler: AutoBackupScheduler,
    ): SetAutoBackupEnabledUseCase =
        SetAutoBackupEnabledUseCase(
            autoBackupRepository = autoBackupRepository,
            scheduler = scheduler,
        )
}
