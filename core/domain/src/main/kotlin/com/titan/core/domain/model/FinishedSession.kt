package com.titan.core.domain.model

/**
 * What [com.titan.core.domain.usecase.FinishSessionUseCase] hands back: the session's own
 * closing state, and everything finishing it moved.
 *
 * Two values rather than one because they answer to different owners. [state] is the
 * lifecycle's — it is what was published to
 * [com.titan.core.domain.repository.SessionStateRepository] and what the state machine's
 * next transition is judged against. [progression] is the gamification cascade's, and is
 * deliberately *not* part of the state: a payoff is something that happened once, at a
 * moment, to one session, while the state is a value the whole app observes and re-reads.
 * Folding the XP into the state would make every later reader of "the session is finished"
 * carry a snapshot of a celebration that has already been shown.
 *
 * Carrying [progression] here rather than leaving the caller to fetch it is the point of the
 * cascade returning a value at all: the summary screen draws records, XP, level-up, streak
 * and unlocks from this and makes no follow-up query, and could not make one that was
 * equivalent — see [SessionProgression].
 *
 * @property state the published [SessionState.Finished], summary and all.
 * @property progression what the cascade did, or **null** when no cascade was wired to run —
 *   see [com.titan.core.domain.usecase.FinishSessionUseCase]'s `completeSession`. Null
 *   rather than an empty [SessionProgression], because the two are different answers and the
 *   empty one is already taken: a cascade that ran and moved nothing reports empty lists and a
 *   null [SessionProgression.streak], and a caller cannot be asked to tell that apart from a
 *   cascade that never happened. "No payoff was calculated" is not "the payoff was nothing".
 */
data class FinishedSession(
    val state: SessionState.Finished,
    val progression: SessionProgression?,
) {

    /** The session's figures — [SessionState.Finished.summary], reached without the hop. */
    val summary: SessionSummary get() = state.summary
}
