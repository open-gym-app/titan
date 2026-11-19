package com.titan.core.data.di

import com.titan.core.domain.notification.ProgressionAnnouncer
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.AddPlannedSetsUseCase
import com.titan.core.domain.usecase.AddSessionExercisesUseCase
import com.titan.core.domain.usecase.DeleteSetUseCase
import com.titan.core.domain.usecase.DiscardSessionUseCase
import com.titan.core.domain.usecase.EditSetUseCase
import com.titan.core.domain.usecase.FinishSessionUseCase
import com.titan.core.domain.usecase.LinkSupersetUseCase
import com.titan.core.domain.usecase.LogSetUseCase
import com.titan.core.domain.usecase.MarkSetCompletedUseCase
import com.titan.core.domain.usecase.PauseSessionUseCase
import com.titan.core.domain.usecase.RemoveSessionExerciseUseCase
import com.titan.core.domain.usecase.RenameSessionUseCase
import com.titan.core.domain.usecase.ReorderSessionExercisesUseCase
import com.titan.core.domain.usecase.RestoreSetUseCase
import com.titan.core.domain.usecase.ResumeSessionUseCase
import com.titan.core.domain.usecase.SessionCompletionPipeline
import com.titan.core.domain.usecase.SessionLifecycleLock
import com.titan.core.domain.usecase.StartPlannedSessionUseCase
import com.titan.core.domain.usecase.StartSessionUseCase
import com.titan.core.domain.usecase.SwapSessionExerciseUseCase
import com.titan.core.domain.usecase.SyncToHealthConnectUseCase
import com.titan.core.domain.usecase.UnlinkSupersetUseCase
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
 * They carry a defaulted parameter that is the domain's own choice rather than a graph edge —
 * the process-wide [com.titan.core.domain.usecase.SessionLifecycleLock.SHARED] that serialises
 * lifecycle transitions. Dagger does not read Kotlin defaults, so constructor injection would
 * mean binding it, which puts the lock's identity in the graph rather than in the domain where
 * the invariant lives.
 *
 * The clock, by contrast, *is* a graph edge: every one of these stamps rows with the [Clock] the
 * graph binds (see `ClockModule`, which binds [Clock.SYSTEM]), so a test that pins the graph's
 * clock — the store screenshots do — gets rows stamped at the moment it pinned rather than at
 * the wall clock's, through the same use cases the app runs.
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
        clock: Clock,
    ): StartSessionUseCase =
        StartSessionUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
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
    ): StartPlannedSessionUseCase =
        StartPlannedSessionUseCase(
            startSession = startSession,
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
        )

    @Provides
    fun providePauseSessionUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): PauseSessionUseCase =
        PauseSessionUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
            lock = SessionLifecycleLock.SHARED,
        )

    @Provides
    fun provideResumeSessionUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): ResumeSessionUseCase =
        ResumeSessionUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
            lock = SessionLifecycleLock.SHARED,
        )

    @Provides
    fun provideFinishSessionUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        inProgressSessionRepository: InProgressSessionRepository,
        completeSession: SessionCompletionPipeline,
        announceProgression: ProgressionAnnouncer,
        syncToHealthConnect: SyncToHealthConnectUseCase,
        clock: Clock,
    ): FinishSessionUseCase =
        FinishSessionUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            inProgressSessionRepository = inProgressSessionRepository,
            clock = clock,
            lock = SessionLifecycleLock.SHARED,
            // The post-session cascade (#145), now that #35 has given `GamificationRepository`
            // a Room-backed binding and there is something to compose it from — see
            // `GamificationUseCaseModule`. Until this argument was passed, `PrDetectionUseCase`
            // had no caller: the engine ran nowhere, `personal_record` stayed empty, and the
            // per-exercise drill-down's `1RM (EST)`, `MAX SET` and `BEST VOL` cards could only
            // ever draw their no-record dash.
            //
            // Non-optional here, as `FinishSessionUseCase` said it would become: a workout that
            // silently pays nothing is exactly the failure the nullable default was holding
            // open, and there is no longer a reason to leave it open.
            completeSession = completeSession,
            // What the cascade earned, said out loud when nobody was watching the recap that
            // would otherwise have celebrated it (#179). Non-optional for the same reason the
            // pipeline above is: the finish that most needs announcing is the one from the idle
            // nudge's "Finish now", where there is no screen at all — and a null here would
            // make that the finish that silently says nothing.
            announce = announceProgression,
            // The finished workout, written to Health Connect when the user has granted the
            // write (#54). Non-optional for the reason the two above are: the finish that would
            // otherwise be missing from Health Connect is the idle nudge's, which has no screen
            // to do it from. Whether anything is written is the permission's business, asked by
            // the use case at the moment it runs.
            syncToHealthConnect = syncToHealthConnect,
        )

    @Provides
    fun provideDiscardSessionUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        inProgressSessionRepository: InProgressSessionRepository,
    ): DiscardSessionUseCase =
        DiscardSessionUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            inProgressSessionRepository = inProgressSessionRepository,
            lock = SessionLifecycleLock.SHARED,
        )

    /*
     * The ones below that rewrite a session's *layout* take the lock, and the rest do not.
     *
     * None of them is a lifecycle transition, but a removal, a reorder, a swap, a link and an
     * unlink are each a read-modify-write over `session_exercise` — read the exercises, compute
     * positions or a grouping from what was read, write back the rows that moved — and two of
     * those interleaved settle on the older snapshot. The swap is in the list for a quieter
     * version of the same reason: it writes the whole row back, so a snapshot taken before a
     * reorder would restore the position that reorder had just changed. The same instance the
     * transitions take, because they contend over the same session: giving the layout writes a
     * lock of their own would serialize them against each other and against nothing else.
     *
     * The rest edit what is *inside* a running session rather than its shape: appending
     * exercises does not read the layout to change it, and the set writes do not touch it at
     * all. See [LogSetUseCase] for what that leaves open on the logging path and where it is
     * closed.
     */

    @Provides
    fun provideAddSessionExercisesUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): AddSessionExercisesUseCase =
        AddSessionExercisesUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
            lock = SessionLifecycleLock.SHARED,
        )

    @Provides
    fun provideAddPlannedSetsUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): AddPlannedSetsUseCase =
        AddPlannedSetsUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
            lock = SessionLifecycleLock.SHARED,
        )

    @Provides
    fun provideRemoveSessionExerciseUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): RemoveSessionExerciseUseCase =
        RemoveSessionExerciseUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
            lock = SessionLifecycleLock.SHARED,
        )

    @Provides
    fun provideReorderSessionExercisesUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): ReorderSessionExercisesUseCase =
        ReorderSessionExercisesUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
            lock = SessionLifecycleLock.SHARED,
        )

    @Provides
    fun provideSwapSessionExerciseUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): SwapSessionExerciseUseCase =
        SwapSessionExerciseUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
            lock = SessionLifecycleLock.SHARED,
        )

    @Provides
    fun provideLinkSupersetUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): LinkSupersetUseCase =
        LinkSupersetUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
            lock = SessionLifecycleLock.SHARED,
        )

    @Provides
    fun provideUnlinkSupersetUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): UnlinkSupersetUseCase =
        UnlinkSupersetUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
            lock = SessionLifecycleLock.SHARED,
        )

    /** No state check either — a session logged last March is renamed the same way. */
    @Provides
    fun provideRenameSessionUseCase(sessionRepository: SessionRepository): RenameSessionUseCase =
        RenameSessionUseCase(sessionRepository)

    @Provides
    fun provideLogSetUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): LogSetUseCase =
        LogSetUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
        )

    @Provides
    fun provideEditSetUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): EditSetUseCase =
        EditSetUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
        )

    @Provides
    fun provideMarkSetCompletedUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): MarkSetCompletedUseCase =
        MarkSetCompletedUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
        )

    @Provides
    fun provideDeleteSetUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): DeleteSetUseCase =
        DeleteSetUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
        )

    @Provides
    fun provideRestoreSetUseCase(
        sessionRepository: SessionRepository,
        sessionStateRepository: SessionStateRepository,
        clock: Clock,
    ): RestoreSetUseCase =
        RestoreSetUseCase(
            sessionRepository = sessionRepository,
            sessionStateRepository = sessionStateRepository,
            clock = clock,
        )
}
