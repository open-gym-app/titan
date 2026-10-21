package com.titan.core.data.di

import com.titan.core.data.repository.InProgressSessionRepositoryImpl
import com.titan.core.domain.repository.InProgressSessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the in-progress workout draft, which is stored as a file rather than in the
 * database — the reason it gets its own module instead of joining
 * [SessionRepositoryModule].
 *
 * `@Singleton` so the write lock inside the implementation is one lock: it is what
 * keeps two concurrent saves off the same scratch file, and a per-injection-site
 * instance would give each caller a mutex nobody else contends for.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InProgressSessionRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindInProgressSessionRepository(impl: InProgressSessionRepositoryImpl): InProgressSessionRepository
}
