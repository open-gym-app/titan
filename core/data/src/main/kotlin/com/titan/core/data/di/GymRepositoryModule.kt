package com.titan.core.data.di

import com.titan.core.data.repository.GymRepositoryImpl
import com.titan.core.domain.repository.GymRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the gym directory contract to its Room-backed implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GymRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindGymRepository(impl: GymRepositoryImpl): GymRepository
}
