package com.titan.core.data.notification

import com.titan.core.domain.timer.RestSkipRequests
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Skip rest" presses, from the notification to whichever screen is alive to hear them.
 *
 * In memory and process-wide, which is the whole of what it needs to be: the press is
 * delivered to this app's own process — a broadcast receiver runs there — and the only
 * possible listener is a `ViewModel` in the same one. What has to outlive the process is the
 * *draft*, and that is written separately by whoever handles the press; see [RestSkipRequests].
 *
 * No replay and no buffer, deliberately. A press is a thing that happened at a moment; a
 * screen opened afterwards must not act on it, because by then the draft already says the rest
 * is over and re-playing it would cancel whatever rest the user has started since.
 * `MutableSharedFlow()` with all three defaults at zero also makes [skip] *suspend* until
 * every current subscriber has taken the value, which is what lets the caller know the live
 * half is done before it moves on.
 */
@Singleton
class RestSkipRequestsImpl @Inject constructor() : RestSkipRequests {

    private val requests = MutableSharedFlow<Long>()

    override val skipped: Flow<Long> = requests.asSharedFlow()

    override suspend fun skip(sessionId: Long) {
        requests.emit(sessionId)
    }
}
