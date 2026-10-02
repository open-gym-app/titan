package com.titan.core.data.di

import com.titan.core.data.repository.LocalDataRepositoryImpl
import com.titan.core.domain.repository.LocalDataRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the repository that reads and erases *everything* stored on the device.
 *
 * Its own module rather than a line in [DatabaseModule] because it is not a database
 * binding: the implementation holds the settings store and the draft file as well, and the
 * whole reason it exists is that no single store is the answer to "all of it".
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocalDataRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindLocalDataRepository(impl: LocalDataRepositoryImpl): LocalDataRepository
}
