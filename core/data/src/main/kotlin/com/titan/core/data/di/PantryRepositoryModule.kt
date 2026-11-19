package com.titan.core.data.di

import com.titan.core.data.repository.PantryRepositoryImpl
import com.titan.core.domain.repository.PantryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class PantryRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPantryRepository(impl: PantryRepositoryImpl): PantryRepository
}
