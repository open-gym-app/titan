package com.opengym.core.data.di

import com.opengym.core.data.repository.TemplateRepositoryImpl
import com.opengym.core.domain.repository.TemplateRepository
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
