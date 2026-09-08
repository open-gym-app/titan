package com.opengym.core.data.di

import com.opengym.core.domain.backup.AutoBackupScheduler
import com.opengym.core.domain.repository.AutoBackupRepository
import com.opengym.core.domain.repository.BackupRepository
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.repository.TemplateDraftRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.domain.usecase.ExportUseCase
import com.opengym.core.domain.usecase.ImportUseCase
import com.opengym.core.domain.usecase.SetAutoBackupEnabledUseCase
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
     * The clock and the page size are both left at their defaults: the real clock is what
     * production stamps a document with, and the page size is a memory/round-trip trade with no
     * per-build answer. A test constructs this directly with both pinned.
     */
    @Provides
    fun provideExportUseCase(
        backupRepository: BackupRepository,
        userPreferencesRepository: UserPreferencesRepository,
    ): ExportUseCase =
        ExportUseCase(
            backupRepository = backupRepository,
            userPreferencesRepository = userPreferencesRepository,
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
