package com.titan.core.data.di

import com.titan.core.data.repository.LoadoutRepositoryImpl
import com.titan.core.domain.repository.LoadoutRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LoadoutRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindLoadoutRepository(impl: LoadoutRepositoryImpl): LoadoutRepository
}
