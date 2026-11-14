package com.titan.core.data.di

import com.titan.core.domain.repository.GymRepository
import com.titan.core.domain.usecase.NearbyGymsUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The gym directory's use cases, wired as [PrUseCaseModule] wires the PR engine's. */
@Module
@InstallIn(SingletonComponent::class)
object GymUseCaseModule {

    @Provides
    fun provideNearbyGymsUseCase(gymRepository: GymRepository): NearbyGymsUseCase =
        NearbyGymsUseCase(gymRepository = gymRepository)
}
