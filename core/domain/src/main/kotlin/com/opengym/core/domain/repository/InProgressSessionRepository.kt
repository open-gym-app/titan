package com.opengym.core.domain.repository

import com.opengym.core.domain.model.ActiveSession
import com.opengym.core.domain.model.ActiveSessionFormat

/**
 * The single in-progress workout draft, saved so a session survives the process that
 * was running it being killed.
 *
 * At most one draft exists at a time, because at most one session runs at a time — the
 * same premise [SessionStateRepository] is built on. There is no id parameter anywhere
 * here on purpose: a draft is a slot, not a collection.
 *
 * ### How this differs from the other two session repositories
 *
 * [SessionRepository] owns the rows and outlives the process; [SessionStateRepository]
 * owns "what is the session doing right now" and does not. This one is the bridge: it
 * is the second question written down in a form that answers it after a restart.
 * [ActiveSession.toSessionState] is what seeds [SessionStateRepository] from a draft on
 * relaunch, and [ActiveSession.restTimerStateAt] is what feeds
 * [com.opengym.core.domain.timer.RestTimerController.restore].
 *
 * ### Contract
 *
 * - [load] returns **null** when no draft exists — nothing has been saved, [clear] was
 *   called, or what was stored cannot be read by this build. All three mean the same
 *   thing to a caller, and none of them is an error.
 * - [save] **overwrites** whatever draft was there, atomically. There is no merge and
 *   no append; the last write wins, whole.
 * - A draft is a snapshot, not a record. The session rows are the record. Losing a
 *   draft costs the user their place in the workout, never their logged sets.
 *
 * ### Where the implementation lives
 *
 * In `:core:data`, and nothing about the storage medium is decided here — only the
 * document, by [ActiveSessionFormat]. What the implementation *does* owe, beyond the
 * contract above, is spelled out on each method: an atomic overwrite, and a clock that
 * matches the one the draft's deadlines were written against.
 */
interface InProgressSessionRepository {

    /**
     * Replaces the stored draft with [session], atomically: a reader concurrent with
     * this call, or one that comes back after the process died in the middle of it,
     * sees either the whole previous draft or the whole new one — never half of either,
     * and never nothing.
     *
     * That is a real requirement rather than a formality. This is called on every set
     * logged and every tick the user changes something, so the odds of a kill landing
     * inside a write are not negligible, and the failure mode is losing a workout in
     * progress to a truncated file. A write-then-rename, a DataStore commit or a
     * single-row transaction all satisfy it; a `File.writeText` over the live document
     * does not.
     *
     * Saving is not what makes the session durable — [SessionRepository] already holds
     * the rows. It is what makes the *place in it* durable.
     */
    suspend fun save(session: ActiveSession)

    /**
     * The stored draft, or **null** when there is none to restore.
     *
     * Null covers every way that can be true, deliberately: nothing was ever saved,
     * the last session was finished and [clear]ed, or the stored document is corrupt
     * or written in a format version this build does not read (see
     * [ActiveSessionFormat.decode]). A caller does the same thing in all of them —
     * start from an empty screen — so distinguishing them would only invite an error
     * path that never runs.
     *
     * A draft that *is* returned may still describe a session that no longer exists on
     * disk, e.g. one deleted from another screen. Restoring should confirm
     * [ActiveSession.sessionId] against [SessionRepository] before reattaching to it.
     *
     * Reading is not idempotent with respect to time: [ActiveSession.restTimerStateAt]
     * and [ActiveSession.elapsedAt] both need "now", and the further the read is from
     * the write the more of the rest has elapsed. That is the design working — see
     * [com.opengym.core.domain.timer.RestTimerSnapshot].
     */
    suspend fun load(): ActiveSession?

    /**
     * Discards the draft, so the next [load] returns null.
     *
     * Called when the session it describes stops being in progress — finished or
     * abandoned. A draft left behind after a session is closed out is what makes the
     * app offer to resume a workout that is already over, so this is not optional
     * cleanup.
     *
     * A no-op when there is no draft; clearing twice is not an error.
     */
    suspend fun clear()
}
