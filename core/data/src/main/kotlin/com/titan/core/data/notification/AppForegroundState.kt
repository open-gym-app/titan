package com.titan.core.data.notification

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the user is looking at this app right now.
 *
 * The one question that decides whether an expiring rest is announced by a notification or by
 * the in-app alert. It has to be asked of the *process* rather than of any activity: the
 * activity behind a workout survives being backgrounded — that is what keeps the countdown
 * running — so "is there an activity" answers yes for a phone that has been in a pocket for a
 * minute, which is precisely the case the notification exists for.
 *
 * An interface with one implementation, because the implementation reads a global the tests
 * cannot drive: [ProcessLifecycleOwner] is a singleton fed by the platform's own activity
 * callbacks, and there is no supported way to put it in `STARTED` from a JVM test.
 */
interface AppForegroundState {

    /** True while at least one of this app's activities is started. */
    suspend fun isInForeground(): Boolean
}

/** [AppForegroundState] over the process lifecycle androidx keeps for us. */
@Singleton
class ProcessForegroundState @Inject constructor() : AppForegroundState {

    /**
     * `Dispatchers.Main.immediate` because [ProcessLifecycleOwner]'s registry is main-thread
     * only, and this is called from a broadcast receiver's coroutine as well as from a
     * ViewModel's. `immediate` so the common case — already on main — costs no dispatch.
     */
    override suspend fun isInForeground(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
}
