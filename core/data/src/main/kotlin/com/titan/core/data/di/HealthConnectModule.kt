package com.titan.core.data.di

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import com.titan.core.data.health.HealthConnectGatewayImpl
import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.health.HealthConnectControls
import com.titan.core.domain.health.ImportFromHealthConnectUseCase
import com.titan.core.domain.health.SyncToHealthConnectUseCase
import com.titan.core.domain.repository.HealthConnectGateway
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.time.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HealthConnectModule {

    @Provides
    @Singleton
    fun provideHealthConnectGateway(
        @ApplicationContext context: Context,
        clock: Clock,
    ): HealthConnectGateway =
        HealthConnectGatewayImpl(
            sdkInt = Build.VERSION.SDK_INT,
            sdkStatus = { HealthConnectClient.getSdkStatus(context) },
            client = { HealthConnectClient.getOrCreate(context) },
            nowMillis = clock::nowMillis,
        )

    @Provides
    fun provideSyncToHealthConnect(
        sessions: SessionRepository,
        gateway: HealthConnectGateway,
        toggles: FeatureToggles,
    ): SyncToHealthConnectUseCase = SyncToHealthConnectUseCase(sessions, gateway, toggles)

    @Provides
    fun provideImportFromHealthConnect(
        sessions: SessionRepository,
        gateway: HealthConnectGateway,
        toggles: FeatureToggles,
        clock: Clock,
    ): ImportFromHealthConnectUseCase = ImportFromHealthConnectUseCase(sessions, gateway, toggles, clock)

    @Provides
    fun provideHealthConnectControls(
        gateway: HealthConnectGateway,
        toggles: FeatureToggles,
    ): HealthConnectControls = HealthConnectControls(gateway, toggles)
}
