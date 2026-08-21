package com.opengym.core.ui.component

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.opengym.core.domain.timer.RestTimerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * It does not fire while the app is gone, and it does not fire *late* on the way back. Nothing
 * here is scheduled with the OS, so a rest that ends while the user is in another app passes
 * unannounced. That needs a notification and an alarm, which is #39's work; this is the in-app
 * half, and it is the half that covers the common case of the phone lying face-up on the bench.
 *
 * Passing unannounced takes a second rule, because the composition usually survives the trip.
 * The screen reads its state with `collectAsStateWithLifecycle`, which stops collecting at
 * `ON_STOP` and delivers the latest value on the way back in — so a rest that ran out twenty
 * minutes ago arrives as a brand-new `Finished` over a `wasRunning` that was never cleared, and
 * the phone rings for a rest the user has long since finished resting. Going away therefore
 * clears the flag: whatever happened while the app was not being watched is history by the time
 * it is delivered, and history is #39's to announce, not this.
 *
 * The flag is set again by the first `Running` that arrives after the trip, which is the next
 * tick — so the cost of the rule is a rest that both ends *and* is returned to inside one
 * second, which passes unannounced. That is the right way round: a buzz missed by a user who
 * was looking at their phone anyway, against a buzz for a rest that ended twenty minutes ago.
 *
 * ### Respecting the system
 *
 * The tone is the user's own notification sound and is not played at all unless the ringer is
 * in its normal mode, so a phone on silent or vibrate stays that way.
 *
 * The buzz is the device's vibrator, and it runs one step wider than the tone: a phone in
 * *vibrate* mode buzzes, because vibrate is the mode that asks for exactly this. Silent stops
 * it, on the same reasoning that stops the tone — a phone told to be still is not overridden
 * from a gym. It is played with notification audio attributes, so the system applies the user's
 * own notification-vibration setting to it rather than this deciding.
 *
 * @param state the rest timer, as the screen already has it.
 * @param enabled false silences it. For the caller that has its own reason not to make a
 *   noise — a preview, a test, a screen showing somebody else's finished workout.
 */
@Composable
fun RestExpiryAlert(state: RestTimerState, enabled: Boolean = true) {
    val context = LocalContext.current
    val tone = rememberNotificationTone(enabled)

    // Whether the last state seen was a countdown in flight. A plain flag rather than the
    // previous state: what is being asked is whether this `Finished` is the end of a rest this
    // composition watched, and every other shade of "what was it before" is the same answer.
    var wasRunning by remember { mutableStateOf(false) }

    // Going away ends the stretch this composition was watching. See the note above: without
    // this, the first state delivered on the way back rings for a rest that ended while the
    // user was elsewhere.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { wasRunning = false }

    LaunchedEffect(state) {
        if (enabled && wasRunning && state is RestTimerState.Finished) {
            // The buzz first, and without suspending: it is the half that reaches a lifter who
            // is not holding the phone, and it should not be queued behind an audio stack.
            context.vibrateRestOver()
            tone.playIfRingerIsOn(context)
        }
        wasRunning = state is RestTimerState.Running
    }
}

/**
 * The device's notification sound, looked up once off the main thread and held for as long as
 * the alert is on screen.
 *
 * Both halves matter. The lookup reads `Settings` and then a `ContentResolver`, which is disk
 * and IPC; doing it inside the alert would put that on the main thread on the exact frame the
 * countdown hits zero and the sheet is coming down — the one frame in a rest that has anything
 * to do. And the [Ringtone] has to be *held*: on devices that release the underlying player
 * when it is collected, a tone owned by nothing but a local can be cut off mid-sound.
 *
 * Null until the lookup lands, and null for good on a device with no notification sound
 * configured or an audio stack that refuses. The rest of the alert treats that as "no tone",
 * which is what it is — the buzz and the screen still say the rest is over.
 */
@Composable
private fun rememberNotificationTone(enabled: Boolean): Ringtone? {
    val context = LocalContext.current
    var tone by remember { mutableStateOf<Ringtone?>(null) }

    LaunchedEffect(context, enabled) {
        if (!enabled) return@LaunchedEffect
        tone = withContext(Dispatchers.IO) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                uri?.let { RingtoneManager.getRingtone(context, it) }
            }.getOrNull()
        }
    }

    // A tone still sounding when the workout screen goes is a noise with nothing behind it.
    DisposableEffect(tone) { onDispose { runCatching { tone?.stop() } } }

    return tone
}

/**
 * Plays the notification sound once, or nothing at all.
 *
 * Silent unless the ringer is in its normal mode — a phone set to vibrate or to silent has
 * been told what to do, and an app that overrode it here would be doing so in a gym. Playing
 * is best-effort for the same reason a draft save is: an audio stack that refuses is not
 * something to interrupt a workout over.
 */
private suspend fun Ringtone?.playIfRingerIsOn(context: Context) {
    val tone = this ?: return
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

    withContext(Dispatchers.IO) { runCatching { tone.play() } }
}

/**
 * Buzzes the phone twice, or not at all.
 *
 * The half of the alert that reaches a lifter who is not holding the device: a pocket or a
 * bench picks up a vibration that a notification tone loses to the room. Two short pulses
 * rather than one long one, because one is indistinguishable from every other notification the
 * phone makes and a pattern is recognisable without being an alarm.
 *
 * This is the real vibrator and not Compose's view-level haptics, which is what was here
 * before. That call is a *tick* — the feedback a long-press gives under a finger already on
 * the glass — and it is far too small to notice from across a bench, which is the one place it
 * had to work. The cost is the `VIBRATE` permission, declared in this module's manifest; it is
 * install-time and not something the user is ever prompted for.
 *
 * Best-effort throughout: a device with no vibrator, or one whose vibrator service refuses,
 * still gets the tone and the screen.
 */
private fun Context.vibrateRestOver() {
    // Vibrate mode buzzes — that is what the mode is for — and only silent stops it. Note this
    // is one notch wider than [playNotificationTone], which needs the ringer fully on.
    val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    if (audio != null && audio.ringerMode == AudioManager.RINGER_MODE_SILENT) return

    val vibrator = defaultVibrator() ?: return
    if (!vibrator.hasVibrator()) return

    runCatching {
        vibrator.vibrate(
            VibrationEffect.createWaveform(REST_OVER_PATTERN, NO_REPEAT),
            VIBRATION_ATTRIBUTES,
        )
    }
}

/** The device's vibrator, by whichever service the platform level exposes it through. */
private fun Context.defaultVibrator(): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

/** Wait, buzz, wait, buzz — in milliseconds, starting from off. */
private val REST_OVER_PATTERN = longArrayOf(0, 180, 120, 180)

/** [VibrationEffect.createWaveform]'s "play it once and stop". */
private const val NO_REPEAT = -1

/**
 * Announced as a notification rather than as an alarm, so the system files it under the
 * setting the user would look for it under — and so it does not claim the exemptions an alarm
 * gets from Do Not Disturb.
 */
private val VIBRATION_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()
