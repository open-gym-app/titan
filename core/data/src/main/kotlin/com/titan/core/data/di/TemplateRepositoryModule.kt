package com.titan.core.data.di

import com.titan.core.data.repository.TemplateRepositoryImpl
import com.titan.core.domain.repository.TemplateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds saved exercise sequences to their Room-backed implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TemplateRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTemplateRepository(impl: TemplateRepositoryImpl): TemplateRepository
}
