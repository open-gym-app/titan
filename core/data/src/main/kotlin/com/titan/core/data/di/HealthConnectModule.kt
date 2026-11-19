package com.titan.core.data.di

import com.titan.core.data.healthconnect.HealthConnectAdapter
import com.titan.core.data.repository.HealthConnectSessionRepositoryImpl
import com.titan.core.domain.healthconnect.HealthConnect
import com.titan.core.domain.repository.HealthConnectSessionRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.ImportFromHealthConnectUseCase
import com.titan.core.domain.usecase.SyncToHealthConnectUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Health Connect (#54): the client, the local side it is compared against, and the two use cases
 * built from them.
 *
 * The use cases are provided here rather than in `SessionUseCaseModule` beside the finish that
 * calls one of them, because they are this integration's and nothing else's — the finish takes
 * the sync as an argument, and finds it in the graph wherever it is bound.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HealthConnectModule {

    @Binds
    abstract fun bindHealthConnect(impl: HealthConnectAdapter): HealthConnect

    @Binds
    abstract fun bindHealthConnectSessionRepository(
        impl: HealthConnectSessionRepositoryImpl,
    ): HealthConnectSessionRepository

    companion object {

        @Provides
        fun provideSyncToHealthConnectUseCase(
            healthConnect: HealthConnect,
            sessions: HealthConnectSessionRepository,
        ): SyncToHealthConnectUseCase = SyncToHealthConnectUseCase(healthConnect, sessions)

        @Provides
        fun provideImportFromHealthConnectUseCase(
            healthConnect: HealthConnect,
            sessions: HealthConnectSessionRepository,
            clock: Clock,
        ): ImportFromHealthConnectUseCase = ImportFromHealthConnectUseCase(healthConnect, sessions, clock)
    }
}
