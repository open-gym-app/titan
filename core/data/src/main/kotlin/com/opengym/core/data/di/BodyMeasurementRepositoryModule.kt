package com.opengym.core.data.di

import com.opengym.core.data.repository.BodyMeasurementRepositoryImpl
import com.opengym.core.domain.repository.BodyMeasurementRepository
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
