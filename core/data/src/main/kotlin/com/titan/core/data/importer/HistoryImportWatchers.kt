package com.titan.core.data.importer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether anyone is watching the import from another app: how many collectors
 * [WorkManagerHistoryImportRunner.observe] has right now.
 *
 * The import screen is its only collector, and it collects only while it is on screen — its
 * state is collected with the lifecycle, and the `WhileSubscribed` behind it lets go a few
 * seconds after the screen stops. So "watched" means "the import screen is showing", which is
 * the one place an import's end is already said. `HistoryImportWorker` posts its finished line
 * whenever nobody is watching: with the app in the background, and also with the user anywhere
 * else in it, which is where the running screen's "you can leave this screen" sends them.
 */
@Singleton
class HistoryImportWatchers @Inject constructor() {

    private val count = AtomicInteger(0)

    /** Whether the import is being watched right now. */
    val anyone: Boolean get() = count.get() > 0

    /** [flow], counted as a watcher for as long as each of its collections lasts. */
    fun <T> counting(flow: Flow<T>): Flow<T> =
        flow
            .onStart { count.incrementAndGet() }
            .onCompletion { count.decrementAndGet() }
}
