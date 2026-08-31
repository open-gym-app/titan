package com.opengym.core.data.di

import com.opengym.core.data.repository.GamificationRepositoryImpl
import com.opengym.core.domain.repository.GamificationRepository
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
