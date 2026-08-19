package com.opengym.core.ui.component

import android.content.Context
import android.media.AudioManager
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.opengym.core.domain.timer.RestTimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowVibrator
import kotlin.time.Duration.Companion.seconds

/**
 * The buzz at the end of a rest, and — mostly — the four ways of reaching [RestTimerState.Finished]
 * that must not raise one.
 *
 * The transition rule is the whole of this component, and it is the kind of thing that is
 * silently wrong: an alert that fires on the *state* instead of on the transition rings once on
 * every recomposition and again every time a finished rest is restored, and nothing about the
 * screen looks any different when it does.
 */
@RunWith(RobolectricTestRunner::class)
class RestTimerAlertTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val screen = ScreenLifecycle().apply { registry.currentState = Lifecycle.State.RESUMED }

    private val lifecycle: LifecycleRegistry get() = screen.registry

    private val vibrator: ShadowVibrator get() = shadowOf(context.defaultVibrator())

    @Test
    fun `a rest that runs out buzzes the phone`() {
        val state = alertOn(running())

        state.value = RestTimerState.Finished

        composeTestRule.runOnIdle {
            assertEquals(
                listOf(0L, 180L, 120L, 180L),
                vibrator.pattern?.toList(),
            )
        }
    }

    @Test
    fun `the buzz is announced as a notification, not as an alarm`() {
        val state = alertOn(running())

        state.value = RestTimerState.Finished

        composeTestRule.runOnIdle {
            // The platform turns the audio attributes the alert passes into the vibration
            // attributes the vibrator actually runs under, which is where the usage has to
            // land: an alarm-usage buzz claims exemptions from Do Not Disturb that a rest
            // between two sets has no business claiming.
            val attributes = vibrator.vibrationAttributesFromLastVibration as VibrationAttributes
            assertEquals(VibrationAttributes.USAGE_NOTIFICATION, attributes.usage)
        }
    }

    @Test
    fun `a phone on vibrate still buzzes`() {
        ringerMode(AudioManager.RINGER_MODE_VIBRATE)
        val state = alertOn(running())

        state.value = RestTimerState.Finished

        composeTestRule.runOnIdle { assertTrue(vibrator.pattern != null) }
    }

    @Test
    fun `a phone on silent stays still`() {
        ringerMode(AudioManager.RINGER_MODE_SILENT)
        val state = alertOn(running())

        state.value = RestTimerState.Finished

        composeTestRule.runOnIdle { assertNothingHappened() }
    }

    @Test
    fun `a rest restored as finished is not an alert`() {
        alertOn(RestTimerState.Finished)

        composeTestRule.runOnIdle { assertNothingHappened() }
    }

    @Test
    fun `a rest that ran out while the user was in another app is not announced on the way back`() {
        val state = alertOn(running())

        // Away. The composition survives — the screen is still on the stack — but the state it
        // reads stops being collected, so nothing is delivered until the user is back.
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        // And back, with the rest twenty minutes over. This is the *first* the screen hears of
        // it, which is exactly what makes it look like a fresh expiry.
        state.value = RestTimerState.Finished

        composeTestRule.runOnIdle { assertNothingHappened() }
    }

    @Test
    fun `a rest that outlives a trip away still rings when it runs out in front of the user`() {
        val state = alertOn(running())

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        // Back before the rest was over, so the next tick arrives under the user's eyes and
        // this expiry is one they are present for.
        state.value = RestTimerState.Running(remaining = 1.seconds, total = 60.seconds)
        composeTestRule.waitForIdle()
        state.value = RestTimerState.Finished

        composeTestRule.runOnIdle { assertTrue(vibrator.pattern != null) }
    }

    @Test
    fun `a rest that was skipped is not an alert`() {
        val state = alertOn(running())

        // What `RestTimerController.cancel` publishes, and the reason it does not publish
        // `Finished`: dismissing a rest must be distinguishable from resting through it.
        state.value = RestTimerState.Idle

        composeTestRule.runOnIdle { assertNothingHappened() }
    }

    @Test
    fun `a disabled alert is silent`() {
        val state = alertOn(running(), enabled = false)

        state.value = RestTimerState.Finished

        composeTestRule.runOnIdle { assertNothingHappened() }
    }

    private fun assertNothingHappened() {
        assertFalse("the phone buzzed", vibrator.isVibrating)
        assertEquals(null, vibrator.pattern)
    }

    /** Puts the alert on screen over a mutable state, and hands the state back to move. */
    private fun alertOn(
        initial: RestTimerState,
        enabled: Boolean = true,
    ): MutableState<RestTimerState> {
        val state = mutableStateOf(initial)
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides screen) {
                RestExpiryAlert(state = state.value, enabled = enabled)
            }
        }
        composeTestRule.waitForIdle()
        return state
    }

    private fun ringerMode(mode: Int) {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode = mode
    }

    private fun running() = RestTimerState.Running(remaining = 2.seconds, total = 60.seconds)

    private fun Context.defaultVibrator(): Vibrator =
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    /**
     * The screen's lifecycle, driven by hand.
     *
     * Provided over [LocalLifecycleOwner] rather than taken from the test rule, because the
     * point of two of these tests is a trip away and back, and the rule's own owner is resumed
     * for the whole run.
     */
    private class ScreenLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
