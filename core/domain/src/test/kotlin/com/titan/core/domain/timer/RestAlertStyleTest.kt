package com.titan.core.domain.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule about what a silenced phone is allowed to do at the end of a rest.
 *
 * Worth a test of its own because it has two callers that cannot see each other — the in-app
 * alert and the notification posted when the app is away — and the whole reason it is a
 * function rather than two `if`s is that they must not drift. A test on the rule is the only
 * place that can be asserted once.
 */
class RestAlertStyleTest {

    @Test
    fun `a silenced phone stays silent`() {
        // A phone told to be still is not overridden from a gym.
        val style = RestAlertStyle.forRingerMode(RingerMode.SILENT)

        assertFalse(style.tone)
        assertFalse(style.vibration)
        assertFalse(style.isAudible)
    }

    @Test
    fun `vibrate mode buzzes and stays quiet`() {
        // The asymmetry that makes this worth stating: the buzz survives one notch further
        // down than the tone, because vibrate is the mode that asks for exactly this.
        val style = RestAlertStyle.forRingerMode(RingerMode.VIBRATE)

        assertFalse(style.tone)
        assertTrue(style.vibration)
        assertTrue(style.isAudible)
    }

    @Test
    fun `an ordinary phone gets both`() {
        val style = RestAlertStyle.forRingerMode(RingerMode.NORMAL)

        assertTrue(style.tone)
        assertTrue(style.vibration)
    }

    @Test
    fun `every ringer position has an answer`() {
        // `forRingerMode` is exhaustive over the enum, so this only fails if somebody adds a
        // position and makes the `when` non-exhaustive by adding an else — which is the change
        // this is here to make loud.
        assertEquals(RingerMode.entries.size, RingerMode.entries.map(RestAlertStyle::forRingerMode).size)
    }
}
