package com.opengym.core.data.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A [BroadcastReceiver] whose work is a suspend function, kept alive with [goAsync] while it
 * runs.
 *
 * Shared by [RestTimerReceiver] and [IdleSessionReceiver], which both handle a broadcast that
 * may arrive at a cold process — a countdown expiring, a session gone quiet — with no screen of
 * the app alive to hold it open otherwise. Without `goAsync` the system is free to kill the
 * process the instant `onReceive` returns, which for a launch-from-cold is well before a suspend
 * function reading a store has come back. The `finish()` is in a `finally` for the reason it
 * always is: a receiver that never finishes its result is a process the system holds open until
 * it decides to complain.
 *
 * `Dispatchers.Main.immediate`, because both receivers' handling starts by reading the process
 * lifecycle, which is main-thread only, and every store below suspends onto its own dispatcher
 * anyway.
 */
abstract class SuspendingBroadcastReceiver : BroadcastReceiver() {

    protected abstract suspend fun handle(
        context: Context,
        intent: Intent,
    )

    final override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                handle(context, intent)
            } finally {
                pending.finish()
            }
        }
    }
}
