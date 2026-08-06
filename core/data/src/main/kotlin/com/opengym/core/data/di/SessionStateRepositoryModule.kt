package com.opengym.core.data.di

import com.opengym.core.data.repository.SessionStateRepositoryImpl
import com.opengym.core.domain.repository.SessionStateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the in-memory lifecycle state of the running session.
 *
 * `@Singleton` is load-bearing here in a way it is not for the Room-backed
 * repositories: those would still behave correctly unscoped, because the database
 * behind them is the shared thing. This one *is* the shared thing — a second
 * instance would be a second, disagreeing answer to "is a session running", so the
 * scope is what makes the four session use cases see each other's transitions.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionStateRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSessionStateRepository(impl: SessionStateRepositoryImpl): SessionStateRepository
}
