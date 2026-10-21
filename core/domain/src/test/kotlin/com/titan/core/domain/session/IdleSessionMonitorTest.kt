package com.titan.core.domain.session

import com.titan.core.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The one number this interface decides, and the two things it has to sit between.
 *
 * `:core:data` owns the alarm and the notification, and is where the monitor's behaviour is
 * tested. What lives here is the threshold itself, which is a product decision rather than an
 * implementation detail: it is what separates "abandoned" from "resting", and getting it wrong
 * in either direction is a notification the user did not want — one about a rest they are still
 * taking, or one that arrives so late the only answer left is to finish the session.
 */
class IdleSessionMonitorTest {

    @Test
    fun `a session may log nothing for half an hour before the app says anything`() {
        assertEquals(30.minutes, IdleSessionMonitor.IDLE_THRESHOLD)
    }

    @Test
    fun `the threshold is comfortably past the longest rest anybody takes`() {
        // The rest-timer picker tops out at five minutes, so a nudge can never be a nudge about
        // a rest — and there is room left over for a lifter ignoring the picker and resting
        // through a phone call. Lowering the threshold under that turns the nudge into noise
        // that gets the whole channel muted, which costs every other thing the app has to say.
        assertTrue(IdleSessionMonitor.IDLE_THRESHOLD > 5.minutes)
        assertTrue(IdleSessionMonitor.IDLE_THRESHOLD > UserPreferences.DEFAULT_REST_DURATION)
    }
}
