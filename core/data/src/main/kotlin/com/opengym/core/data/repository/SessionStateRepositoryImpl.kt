package com.opengym.core.data.repository

import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.repository.SessionStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The lifecycle state of the running session, held in memory for the life of the
 * process.
 *
 * In memory on purpose, and the one repository here that touches no storage at all.
 * The state answers "what is the session doing right now", which stops being true
 * the moment the process dies; the durable half of the question is
 * `SessionRepository`'s `is_in_progress` row and
 * `InProgressSessionRepository`'s draft, and `ResumeSessionUseCase` is what puts
 * the two back together on relaunch. Persisting this as well would give the app two
 * answers that can disagree.
 *
 * A [MutableStateFlow] satisfies the contract's two demands directly: it is
 * conflated and latest-wins, and it replays its current value to every new
 * collector, so `observe().first()` returns without a write having to happen first.
 * `@Singleton` is what makes that one shared fact rather than one per injection
 * site — this is the rare stateful binding where the scope is the behaviour.
 */
@Singleton
class SessionStateRepositoryImpl @Inject constructor() : SessionStateRepository {

    private val state = MutableStateFlow<SessionState>(SessionState.Idle)

    override fun observe(): Flow<SessionState> = state.asStateFlow()

    override suspend fun update(state: SessionState) {
        this.state.value = state
    }
}
