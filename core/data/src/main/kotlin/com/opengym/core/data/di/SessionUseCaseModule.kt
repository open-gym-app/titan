package com.opengym.core.data.di

import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.usecase.SessionLifecycleLock
import com.opengym.core.domain.usecase.StartPlannedSessionUseCase
import com.opengym.core.domain.usecase.StartSessionUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The session lifecycle use cases, constructed from the repositories bound alongside.
 *
 * ### Why the use cases are wired from `:core:data`
 *
 * They belong to `:core:domain`, which is a plain Kotlin JVM library with no Android
 * and no Hilt on its classpath — the thing that makes it testable without a runtime is
 * also what stops it declaring its own bindings. `:app` is the other candidate and is
 * the composition root, but every other binding in the graph is already here, and
 * splitting them across two modules by which Gradle module happens to own the *type*
 * would mean checking two places for the answer to "what is bound".
 *
 * ### Why `@Provides` and not `@Inject constructor`
 *
 * Both of these carry defaulted parameters that are the domain's own choices rather
 * than graph edges — [Clock.SYSTEM], and the process-wide
 * [com.opengym.core.domain.usecase.SessionLifecycleLock.SHARED] that serialises
 * lifecycle transitions. Dagger does not read Kotlin defaults, so constructor injection
 * would mean binding both, which puts the lock's identity in the graph rather than in
 * the domain where the invariant lives.
 *
 * Unscoped, per ARCHITECTURE.md's table: these are stateless compositions of scoped
 * repositories, so a fresh instance per injection site costs an allocation and nothing
 * else. What must be shared is shared inside the domain — see the lock above.
 */
@Module
@InstallIn(SingletonComponent::class)
object SessionUseCaseModule {

    @Provides
    fun provideStartSessionUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
    ): StartSessionUseCase = StartSessionUseCase(
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
        clock = Clock.SYSTEM,
        // Named rather than defaulted, because this is the argument the invariant rests
        // on: one lock per process is what makes two concurrent starts queue instead of
        // both passing the check. An unscoped provider handing each injection site its
        // own use case is fine exactly because the lock is not one of the things it
        // constructs.
        lock = SessionLifecycleLock.SHARED,
    )

    @Provides
    fun provideStartPlannedSessionUseCase(
        startSession: StartSessionUseCase,
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
    ): StartPlannedSessionUseCase = StartPlannedSessionUseCase(
        startSession = startSession,
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
    )
}
