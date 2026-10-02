package com.titan.core.data.di

import com.titan.core.data.repository.CollectedItemRepositoryImpl
import com.titan.core.domain.repository.CollectedItemRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the collectible inventory contract to its Room-backed implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CollectedItemRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCollectedItemRepository(impl: CollectedItemRepositoryImpl): CollectedItemRepository
}
