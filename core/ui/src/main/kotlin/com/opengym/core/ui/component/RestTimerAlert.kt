package com.opengym.core.ui.component

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.opengym.core.domain.timer.RestTimerState

/**
 * Says the rest is over, to somebody who is not looking at the phone.
 *
 * Which is the entire point: a lifter sets the phone down, does something else for ninety
 * seconds, and the screen is the one channel that cannot reach them. A buzz and the device's
 * own notification tone can.
 *
 * ### It fires on the transition, not on the state
 *
 * Only a countdown that ran out *while this was on screen* rings — a
 * [RestTimerState.Running] that became [RestTimerState.Finished]. Everything else that can
 * produce a `Finished` is silent, and each of them for a reason:
 *
 * - **A restored rest.** A draft whose deadline passed while the process was dead comes back
 *   finished, and ringing then would be an alarm for a rest that ended twenty minutes ago.
 *   `RestTimerSnapshot` documents this from the other side: restoring `Finished` is a display
 *   fact, not an alert.
 * - **A skipped rest.** `RestTimerController.cancel` lands on [RestTimerState.Idle] precisely
 *   so that dismissing a rest is distinguishable from resting through it.
 * - **A rest shortened past zero.** That one *does* ring, and should: the user asked for the
 *   rest to be over now rather than in fifteen seconds, which is the same event arriving early.
 *
 * ### What it does not do
 *
 * It does not fire while the app is gone. Nothing here is scheduled with the OS, so a process
 * that has been killed — or a rest that ends while the user is in another app long enough for
 * this composition to be torn down — passes unannounced. That needs a notification and an
 * alarm, which is #39's work; this is the in-app half, and it is the half that covers the
 * common case of the phone lying face-up on the bench.
 *
 * ### Respecting the system
 *
 * The tone is the user's own notification sound and is not played at all unless the ringer is
 * in its normal mode, so a phone on silent or vibrate stays that way. The buzz goes through
 * Compose's [androidx.compose.ui.hapticfeedback.HapticFeedback], which is the platform's
 * view-level haptics and obeys the system haptic setting — no `VIBRATE` permission is asked
 * for and none is needed.
 *
 * @param state the rest timer, as the screen already has it.
 * @param enabled false silences it. For the caller that has its own reason not to make a
 *   noise — a preview, a test, a screen showing somebody else's finished workout.
 */
@Composable
fun RestExpiryAlert(state: RestTimerState, enabled: Boolean = true) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // Whether the last state seen was a countdown in flight. A plain flag rather than the
    // previous state: what is being asked is whether this `Finished` is the end of a rest this
    // composition watched, and every other shade of "what was it before" is the same answer.
    var wasRunning by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (enabled && wasRunning && state is RestTimerState.Finished) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            context.playNotificationTone()
        }
        wasRunning = state is RestTimerState.Running
    }
}

/**
 * Plays the device's notification sound once, or nothing at all.
 *
 * Silent unless the ringer is in its normal mode — a phone set to vibrate or to silent has
 * been told what to do, and an app that overrode it here would be doing so in a gym. The
 * lookup is best-effort for the same reason a draft save is: a device with no notification
 * sound configured, or an audio stack that refuses, is not something to interrupt a workout
 * over.
 */
private fun Context.playNotificationTone() {
    val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

    runCatching {
        val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        RingtoneManager.getRingtone(this, tone)?.play()
    }
}
