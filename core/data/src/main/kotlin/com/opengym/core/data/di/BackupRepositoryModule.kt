package com.opengym.core.data.di

import com.opengym.core.data.repository.BackupRepositoryImpl
import com.opengym.core.domain.repository.BackupRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the row-level backup contract to its Room-backed implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository
}
