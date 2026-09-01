package com.opengym.core.ui.component

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.opengym.core.ui.theme.LocalReducedMotion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The level sweep, driven by a held clock.
 *
 * ### Why this is a test and not a screenshot
 *
 * The reference images capture the sweep's *resting* state, because they are recorded under
 * reduced motion — which is the right thing for a reference and says nothing at all about the
 * animation. What matters here happens between frames: that four levels gained are played as four
 * crossings rather than one, in order, with the number changing only after the bar has arrived.
 * A still cannot show that. A held clock can, and it does it deterministically, on the JVM,
 * without a device.
 *
 * `mainClock.autoAdvance = false` is the whole technique: composition advances only when this
 * test says so, so each assertion is made at a known instant rather than at whatever moment the
 * assertion happened to run.
 */
@RunWith(RobolectricTestRunner::class)
class LevelSweepTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a run of levels is played one crossing at a time, in order`() {
        val sweep = sweepFrom(fromLevel = 3, toLevel = 5)

        // Nothing has been advanced: the user is still where the session found them. A build that
        // reported the outcome and animated toward it would already be at 5 here.
        assertEquals(3, sweep().level)
        assertEquals(0, sweep().crossings)

        // Through the first threshold — one full bar, then the beat it holds for.
        composeTestRule.mainClock.advanceTimeBy(ONE_CROSSING)
        assertEquals(4, sweep().level)
        assertEquals(1, sweep().crossings)

        // And the second. Two levels gained are two announcements, which is what lets the
        // banner give each one its own flourish.
        composeTestRule.mainClock.advanceTimeBy(ONE_CROSSING)
        assertEquals(5, sweep().level)
        assertEquals(2, sweep().crossings)
    }

    @Test
    fun `the number changes only after the bar has arrived`() {
        val sweep = sweepFrom(fromLevel = 3, toLevel = 4)

        // Mid-sweep: the bar is moving and the level has not changed. This is the ordering the
        // hold exists to protect — a level that ticked over while the bar was still travelling
        // would leave the crossing with no moment a viewer could catch.
        composeTestRule.mainClock.advanceTimeBy(MID_SWEEP)
        assertEquals(3, sweep().level)
        assertEquals(0, sweep().crossings)
        assertEquals(true, sweep().fraction.value > 0f)
    }

    @Test
    fun `the sweep settles on where the user actually stands`() {
        val sweep = sweepFrom(fromLevel = 3, toLevel = 4, toFraction = 0.12f)

        composeTestRule.mainClock.advanceTimeBy(WHOLE_SEQUENCE)

        assertEquals(4, sweep().level)
        assertEquals(0.12f, sweep().fraction.value, TOLERANCE)
    }

    @Test
    fun `a session that crossed nothing announces nothing`() {
        val sweep = sweepFrom(fromLevel = 4, toLevel = 4, toFraction = 0.6f)

        composeTestRule.mainClock.advanceTimeBy(WHOLE_SEQUENCE)

        // One sweep to where they now are, and no crossing — so the banner's flourish, which is
        // keyed on `crossings`, never fires for an ordinary session.
        assertEquals(4, sweep().level)
        assertEquals(0, sweep().crossings)
        assertEquals(0.6f, sweep().fraction.value, TOLERANCE)
    }

    @Test
    fun `reduced motion lands on the outcome with no ceremony to sit through`() {
        val sweep = sweepFrom(fromLevel = 3, toLevel = 5, toFraction = 0.12f, reducedMotion = true)

        // No clock advanced at all. The final level and fraction are already drawn, and no
        // crossing is ever announced — a user who asked for stillness gets the answer rather
        // than a faster version of the ceremony.
        assertEquals(5, sweep().level)
        assertEquals(0, sweep().crossings)
        assertEquals(0.12f, sweep().fraction.value, TOLERANCE)
    }

    /**
     * Composes a sweep and hands back a reader for it.
     *
     * A getter rather than the value, because the composable returns a fresh [LevelSweep] as the
     * run progresses — a captured reference would be asserted against the instant it was taken.
     */
    private fun sweepFrom(
        fromLevel: Int,
        toLevel: Int,
        fromFraction: Float = 0.2f,
        toFraction: Float = 0.3f,
        reducedMotion: Boolean = false,
    ): () -> LevelSweep {
        composeTestRule.mainClock.autoAdvance = false

        lateinit var latest: LevelSweep
        composeTestRule.setContent {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                latest = rememberLevelSweep(
                    fromLevel = fromLevel,
                    fromFraction = fromFraction,
                    toLevel = toLevel,
                    toFraction = toFraction,
                )
            }
        }
        return { latest }
    }

    private companion object {

        /**
         * Comfortably past one sweep and its hold.
         *
         * Deliberately not the component's own constants: a test that read them would pass
         * whatever they were changed to, including to zero. This is "long enough that a crossing
         * must have happened by now", which is the property worth asserting.
         */
        const val ONE_CROSSING = 1_200L

        /** Into a sweep but nowhere near its end. */
        const val MID_SWEEP = 200L

        /** Past every crossing and the final settle. */
        const val WHOLE_SEQUENCE = 5_000L

        const val TOLERANCE = 0.001f
    }
}
