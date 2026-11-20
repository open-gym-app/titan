package com.titan.core.data.di

import com.titan.core.data.healthconnect.HealthConnectAdapter
import com.titan.core.data.healthconnect.WorkManagerHealthConnectBodySyncScheduler
import com.titan.core.data.healthconnect.WorkManagerHealthConnectSyncScheduler
import com.titan.core.data.repository.HealthConnectSessionRepositoryImpl
import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.healthconnect.HealthConnectBodySyncScheduler
import com.titan.core.domain.healthconnect.HealthConnectSyncScheduler
import com.titan.core.domain.repository.BodyMeasurementRepository
import com.titan.core.domain.repository.HealthConnectSessionRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.ImportBodyFromHealthConnectUseCase
import com.titan.core.domain.usecase.ImportFromHealthConnectUseCase
import com.titan.core.domain.usecase.SyncBodyToHealthConnectUseCase
import com.titan.core.domain.usecase.SyncToHealthConnectUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Health Connect (#54, #350): the client, the local side it is compared against, the use cases
 * built from them — a sync and an import each for sessions and for body measurements — and the
 * WorkManager queues the two syncs run from.
 *
 * The use cases are provided here rather than in `SessionUseCaseModule` beside the finish and
 * the rename that ask for a sync, because they are this integration's and nothing else's — those
 * two take the queue as an argument, and find it in the graph wherever it is bound.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HealthConnectModule {

    @Binds
    abstract fun bindHealthConnect(impl: HealthConnectAdapter): HealthConnect

    @Binds
    abstract fun bindHealthConnectSyncScheduler(impl: WorkManagerHealthConnectSyncScheduler): HealthConnectSyncScheduler

    @Binds
    abstract fun bindHealthConnectBodySyncScheduler(
        impl: WorkManagerHealthConnectBodySyncScheduler,
    ): HealthConnectBodySyncScheduler

    @Binds
    abstract fun bindHealthConnectSessionRepository(
        impl: HealthConnectSessionRepositoryImpl,
    ): HealthConnectSessionRepository

    companion object {

        @Provides
        fun provideSyncToHealthConnectUseCase(
            healthConnect: HealthConnect,
            sessions: HealthConnectSessionRepository,
            clock: Clock,
        ): SyncToHealthConnectUseCase = SyncToHealthConnectUseCase(healthConnect, sessions, clock)

        @Provides
        fun provideImportFromHealthConnectUseCase(
            healthConnect: HealthConnect,
            sessions: HealthConnectSessionRepository,
            clock: Clock,
        ): ImportFromHealthConnectUseCase = ImportFromHealthConnectUseCase(healthConnect, sessions, clock)

        @Provides
        fun provideSyncBodyToHealthConnectUseCase(
            healthConnect: HealthConnect,
            measurements: BodyMeasurementRepository,
        ): SyncBodyToHealthConnectUseCase = SyncBodyToHealthConnectUseCase(healthConnect, measurements)

        @Provides
        fun provideImportBodyFromHealthConnectUseCase(
            healthConnect: HealthConnect,
            measurements: BodyMeasurementRepository,
            clock: Clock,
        ): ImportBodyFromHealthConnectUseCase = ImportBodyFromHealthConnectUseCase(healthConnect, measurements, clock)
    }
}
