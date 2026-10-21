package com.titan.core.data.di

import com.titan.core.data.repository.SessionRepositoryImpl
import com.titan.core.domain.repository.SessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the persisted half of a workout session — the rows in `workout_session`,
 * `session_exercise` and `workout_set`.
 *
 * Separate from [SessionStateRepositoryModule] and
 * [InProgressSessionRepositoryModule] because the three answer different questions
 * with different lifetimes, and their bindings are the place that distinction is
 * easiest to lose: this one outlives the process, the other two do not.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository
}
