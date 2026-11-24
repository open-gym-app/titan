package com.titan.core.data.di

import com.titan.core.data.repository.HistoryImportRepositoryImpl
import com.titan.core.domain.repository.HistoryImportRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the store-wide transaction an import from another app runs in. */
@Module
@InstallIn(SingletonComponent::class)
abstract class HistoryImportRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindHistoryImportRepository(impl: HistoryImportRepositoryImpl): HistoryImportRepository
}
