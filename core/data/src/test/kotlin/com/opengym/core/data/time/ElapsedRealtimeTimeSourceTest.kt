package com.opengym.core.data.time

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration

/**
 * That the rest timer's clock is the one that counts while the device is asleep.
 *
 * Robolectric's `SystemClock` is the only place this can be asserted: `elapsedRealtime` is a
 * platform reading with no seam of its own, and the whole reason this class exists is that the
 * obvious substitute — `TimeSource.Monotonic`, i.e. `System.nanoTime` — stops during deep
 * sleep. Advancing the shadow clock is exactly the event that would be missed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ElapsedRealtimeTimeSourceTest {

    @Test
    fun `a mark measures the time that passes after it`() {
        val mark = ElapsedRealtimeTimeSource.markNow()

        ShadowSystemClock.advanceBy(JavaDuration.ofMillis(1_500))

        assertEquals(1_500.milliseconds, mark.elapsedNow())
    }

    @Test
    fun `a fresh mark has measured nothing`() {
        ShadowSystemClock.advanceBy(JavaDuration.ofSeconds(30))

        // The reading is taken at `markNow`, so time that passed before it is not owed back.
        assertEquals(0.milliseconds, ElapsedRealtimeTimeSource.markNow().elapsedNow())
    }

    @Test
    fun `two marks measure independently`() {
        val first = ElapsedRealtimeTimeSource.markNow()
        ShadowSystemClock.advanceBy(JavaDuration.ofSeconds(30))
        val second = ElapsedRealtimeTimeSource.markNow()
        ShadowSystemClock.advanceBy(JavaDuration.ofSeconds(60))

        assertEquals(90.seconds, first.elapsedNow())
        assertEquals(60.seconds, second.elapsedNow())
    }
}
