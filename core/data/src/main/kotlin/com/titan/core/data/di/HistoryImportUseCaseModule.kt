package com.titan.core.data.di

import com.titan.core.domain.backup.BackupLock
import com.titan.core.domain.repository.BodyMeasurementRepository
import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.repository.HistoryImportRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.TemplateRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.ImportHistoryUseCase
import com.titan.core.domain.usecase.SessionCompletionPipeline
import com.titan.core.domain.usecase.SessionLifecycleLock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The import from another app, composed.
 *
 * Its own module rather than a line in [BackupUseCaseModule], because it is not a backup: it
 * merges a foreign history into the user's own rather than replacing the device with Titan's.
 * Unscoped, like every use-case module here.
 */
@Module
@InstallIn(SingletonComponent::class)
object HistoryImportUseCaseModule {

    /**
     * Takes the completion pipeline [GamificationUseCaseModule] composes — the same instance a
     * finished workout is paid through — because "indistinguishable from a logged history" is
     * only true if both go through one cascade. Both locks are the process-wide ones, named here
     * for the reason [BackupUseCaseModule] names them.
     */
    @Provides
    fun provideImportHistoryUseCase(
        historyImportRepository: HistoryImportRepository,
        exerciseRepository: ExerciseRepository,
        sessionRepository: SessionRepository,
        templateRepository: TemplateRepository,
        bodyMeasurementRepository: BodyMeasurementRepository,
        completeSession: SessionCompletionPipeline,
        clock: Clock,
    ): ImportHistoryUseCase =
        ImportHistoryUseCase(
            historyImportRepository = historyImportRepository,
            exerciseRepository = exerciseRepository,
            sessionRepository = sessionRepository,
            templateRepository = templateRepository,
            bodyMeasurementRepository = bodyMeasurementRepository,
            completeSession = completeSession,
            clock = clock,
            backupLock = BackupLock.SHARED,
            sessionLock = SessionLifecycleLock.SHARED,
        )
}
