package com.titan.core.data.di

import com.titan.core.data.repository.GamificationRepositoryImpl
import com.titan.core.domain.repository.GamificationRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the gamification contract to its Room-backed implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GamificationRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindGamificationRepository(impl: GamificationRepositoryImpl): GamificationRepository
}
