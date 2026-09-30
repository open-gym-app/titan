package com.titan.core.data.di

import com.titan.core.data.repository.TemplateDraftRepositoryImpl
import com.titan.core.domain.repository.TemplateDraftRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the half-built template draft, which is stored as a file rather than in the database —
 * the reason it gets its own module instead of joining [TemplateRepositoryModule].
 *
 * `@Singleton` for [InProgressSessionRepositoryModule]'s reason: the write lock inside the
 * implementation is only a lock if there is one of it.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TemplateDraftRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTemplateDraftRepository(impl: TemplateDraftRepositoryImpl): TemplateDraftRepository
}
