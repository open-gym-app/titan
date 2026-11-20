package com.titan.core.domain.healthconnect

/**
 * Asks for one session to be written to Health Connect, later and somewhere else.
 *
 * The callers are the places a session's record goes out of date — the finish that closes it
 * out, a rename, a note — and none of them should wait on the write. It is a call into another
 * app's provider: on Android 13 and below that can mean starting the provider's process from
 * cold, and on every version it begins with a call to read the grant, which most users, who
 * never connected Health Connect, answer with a refusal. A workout screen that held the recap
 * back for that would be paying for an integration the user does not use.
 *
 * Not a coroutine launched on some application scope, because of the idle nudge's "Finish now":
 * that finish runs in a process the broadcast may have started from cold, and once the receiver
 * returns nothing keeps the process alive for a write still in flight. The implementation in
 * `:core:data` hands the write to WorkManager, which survives that and retries a provider that
 * failed.
 *
 * Fire and forget: whether anything is written is [com.titan.core.domain.usecase.SyncToHealthConnectUseCase]'s
 * decision when the work runs — whether the write is granted, whether the session is finished,
 * whether it came from Health Connect in the first place. Asking for a session that turns out to
 * need nothing costs one empty run.
 */
fun interface HealthConnectSyncScheduler {

    /**
     * Writes [sessionId]'s record as it stands when the work runs. A second request for the same
     * session before the first has run replaces it, so a burst of edits ends in one write of
     * the last state.
     */
    fun requestSync(sessionId: Long)
}
