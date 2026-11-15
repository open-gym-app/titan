package com.titan.core.data.di

import com.titan.core.data.repository.ProgressPhotoRepositoryImpl
import com.titan.core.domain.repository.ProgressPhotoRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the progress photo store. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProgressPhotoRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindProgressPhotoRepository(impl: ProgressPhotoRepositoryImpl): ProgressPhotoRepository
}
