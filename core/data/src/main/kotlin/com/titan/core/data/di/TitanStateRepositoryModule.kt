package com.titan.core.data.di

import com.titan.core.data.repository.TitanStateRepositoryImpl
import com.titan.core.domain.repository.TitanStateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TitanStateRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTitanStateRepository(impl: TitanStateRepositoryImpl): TitanStateRepository
}
