package com.titan.core.data.di

import com.titan.core.data.repository.ProgressionRepositoryImpl
import com.titan.core.domain.repository.ProgressionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the progression-aggregate contract to its Room-backed implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProgressionRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindProgressionRepository(impl: ProgressionRepositoryImpl): ProgressionRepository
}
