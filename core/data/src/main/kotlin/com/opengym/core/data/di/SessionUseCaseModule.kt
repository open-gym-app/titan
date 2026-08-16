package com.opengym.core.data.di

import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.usecase.AddSessionExercisesUseCase
import com.opengym.core.domain.usecase.DiscardSessionUseCase
import com.opengym.core.domain.usecase.FinishSessionUseCase
import com.opengym.core.domain.usecase.LinkSupersetUseCase
import com.opengym.core.domain.usecase.LogSetUseCase
import com.opengym.core.domain.usecase.PauseSessionUseCase
import com.opengym.core.domain.usecase.RemoveSessionExerciseUseCase
import com.opengym.core.domain.usecase.RenameSessionUseCase
import com.opengym.core.domain.usecase.ResumeSessionUseCase
import com.opengym.core.domain.usecase.SessionLifecycleLock
import com.opengym.core.domain.usecase.StartPlannedSessionUseCase
import com.opengym.core.domain.usecase.StartSessionUseCase
import com.opengym.core.domain.usecase.UnlinkSupersetUseCase
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

    @Provides
    fun providePauseSessionUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
    ): PauseSessionUseCase = PauseSessionUseCase(
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
        clock = Clock.SYSTEM,
        lock = SessionLifecycleLock.SHARED,
    )

    @Provides
    fun provideResumeSessionUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
    ): ResumeSessionUseCase = ResumeSessionUseCase(
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
        clock = Clock.SYSTEM,
        lock = SessionLifecycleLock.SHARED,
    )

    @Provides
    fun provideFinishSessionUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        inProgressSessionRepository: InProgressSessionRepository,
    ): FinishSessionUseCase = FinishSessionUseCase(
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
        inProgressSessionRepository = inProgressSessionRepository,
        clock = Clock.SYSTEM,
        lock = SessionLifecycleLock.SHARED,
    )

    @Provides
    fun provideDiscardSessionUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        inProgressSessionRepository: InProgressSessionRepository,
    ): DiscardSessionUseCase = DiscardSessionUseCase(
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
        inProgressSessionRepository = inProgressSessionRepository,
        lock = SessionLifecycleLock.SHARED,
    )

    /*
     * The six below take no lock, because none of them is a lifecycle transition: they edit
     * what is *inside* a running session rather than moving it between states. See
     * [LogSetUseCase] for why that is safe.
     */

    @Provides
    fun provideAddSessionExercisesUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
    ): AddSessionExercisesUseCase = AddSessionExercisesUseCase(
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
        clock = Clock.SYSTEM,
    )

    @Provides
    fun provideRemoveSessionExerciseUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
    ): RemoveSessionExerciseUseCase = RemoveSessionExerciseUseCase(
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
    )

    @Provides
    fun provideLinkSupersetUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
    ): LinkSupersetUseCase = LinkSupersetUseCase(
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
        clock = Clock.SYSTEM,
    )

    @Provides
    fun provideUnlinkSupersetUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
    ): UnlinkSupersetUseCase = UnlinkSupersetUseCase(
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
        clock = Clock.SYSTEM,
    )

    /** No state check either — a session logged last March is renamed the same way. */
    @Provides
    fun provideRenameSessionUseCase(
        sessionRepository: SessionRepository,
    ): RenameSessionUseCase = RenameSessionUseCase(sessionRepository)

    @Provides
    fun provideLogSetUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
    ): LogSetUseCase = LogSetUseCase(
        sessionRepository = sessionRepository,
        sessionStateRepository = sessionStateRepository,
        clock = Clock.SYSTEM,
    )
}
