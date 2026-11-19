package com.titan.core.data.di

import com.titan.core.data.repository.UnlockRepositoryImpl
import com.titan.core.domain.repository.UnlockRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UnlockModule {

    @Binds
    @Singleton
    abstract fun bindUnlockRepository(impl: UnlockRepositoryImpl): UnlockRepository
}
