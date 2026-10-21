package com.titan.core.data.di

import com.titan.core.data.repository.BodyMeasurementRepositoryImpl
import com.titan.core.domain.repository.BodyMeasurementRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the body-measurement contract to its Room-backed implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BodyMeasurementRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindBodyMeasurementRepository(impl: BodyMeasurementRepositoryImpl): BodyMeasurementRepository
}
