package com.opengym.core.data.di

import com.opengym.core.data.repository.PersonalRecordRepositoryImpl
import com.opengym.core.domain.repository.PersonalRecordRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the personal-record contract to its Room-backed implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PersonalRecordRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindPersonalRecordRepository(impl: PersonalRecordRepositoryImpl): PersonalRecordRepository
}
