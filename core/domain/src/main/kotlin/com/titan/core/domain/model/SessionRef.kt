package com.titan.core.domain.model

/**
 * The one workout session a caller means, named precisely enough to be refused when it is not
 * the one running.
 *
 * ### Why the lifecycle use cases take one
 *
 * Every lifecycle transition acts on whichever session the state machine has open, because there
 * is only ever one. That is safe for a caller that is *looking at* the open session, and wrong for
 * one that merely names a session: a workout screen opened from a link, or a notification button
 * pressed half an hour after it was posted. Both can name a session that is over, missing or
 * replaced while another is running — and without this, END, DISCARD or "Finish now" on that page
 * or button closes out the workout that is running instead. Handed one of these, the use case
 * refuses with [SessionError.NotTheOpenSession] unless the open session is the one named.
 *
 * ### [createdAt] is for callers outside the process
 *
 * The id alone is enough for a caller inside the app, which read it off a row a moment ago. A
 * caller that was handed the id by the system — a broadcast, an alarm, a pending intent from a
 * notification — may be holding it across a backup restore, which writes back session ids the
 * device has used before for workouts that are not the ones the ids named when the intent was
 * built. The row's creation time travels with the id for those callers, and a row whose
 * `created_at` differs is a different workout under a reused number.
 *
 * Null means the caller accepts whatever row has [sessionId] now, which is what an in-process
 * caller that has just read the row wants.
 *
 * @param sessionId the `workout_session` row.
 * @param createdAt that row's `created_at`, in epoch milliseconds, or null to match on the id
 *   alone.
 */
data class SessionRef(
    val sessionId: Long,
    val createdAt: Long? = null,
) {

    /** Whether [session] is the row this names: the same id, and the same creation time if one was given. */
    fun matches(session: WorkoutSession): Boolean =
        session.id == sessionId && (createdAt == null || session.createdAt == createdAt)

    companion object {

        /** A reference to [session] that also holds its creation time, for a caller outside the process. */
        fun of(session: WorkoutSession): SessionRef = SessionRef(sessionId = session.id, createdAt = session.createdAt)
    }
}
