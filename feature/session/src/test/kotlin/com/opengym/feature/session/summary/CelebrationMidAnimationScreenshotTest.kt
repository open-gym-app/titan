package com.opengym.feature.session.summary

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.opengym.core.ui.theme.LocalReducedMotion
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The celebration **mid-animation**, as a reference image.
 *
 * ### Why this exists, when the previews already capture the card
 *
 * Because they capture it at rest. Every `@Preview` here renders under reduced motion, which is
 * right for a reference — it is deterministic and it is the accessible state — but it means the
 * confetti are not drawn at all in any of them, and the bar is always at its final fraction.
 *
 * That blind spot let a real bug through: the burst was sized with `fillMaxSize` inside a `Box`
 * whose height comes from its other child, under a `LazyColumn`'s unbounded height constraint. It
 * measured to nothing, so every piece landed at y=0 and the effect drew as a row of dots welded to
 * the top edge. Nothing failed, because nothing looked at a frame where confetti should be
 * falling. This is that frame.
 *
 * ### It is deterministic despite being an animation
 *
 * Two things pin it. The clock is **held** — `autoAdvance = false` — so the capture happens at an
 * exact instant rather than whenever the test got there. And the burst's scatter is seeded from
 * the crossing index rather than from a random draw, so the same instant always produces the same
 * pieces in the same places. See `ConfettiBurst`.
 *
 * [MID_BURST_MILLIS] lands inside the first sweep, while pieces are spread down the banner and
 * well before any of them has faded — the part of the animation with the most to be wrong about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CelebrationMidAnimationScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the confetti fall through the banner rather than piling on its top edge`() {
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            // Explicitly *off*: this is the one capture in the module that wants the animation.
            CompositionLocalProvider(LocalReducedMotion provides false) {
                OpenGymTheme {
                    SessionSummaryScreen(
                        state = previewState(celebration = previewMilestoneCelebration()),
                        onEvent = {},
                        onExit = {},
                    )
                }
            }
        }

        composeTestRule.mainClock.advanceTimeBy(MID_RAIN_MILLIS)
        composeTestRule.onRoot().captureRoboImage(RAIN_REFERENCE)
    }

    @Test
    fun `a milestone detonates outward instead of raining down`() {
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                OpenGymTheme {
                    SessionSummaryScreen(
                        state = previewState(celebration = previewMilestoneCelebration()),
                        onEvent = {},
                        onExit = {},
                    )
                }
            }
        }

        // Past both crossings, into the burst the *milestone* throws. The two references
        // differ in the shape of the scatter — falling in a column from the top edge against
        // spreading outward from behind the number — which is the difference the whole
        // `LevelCeremony` distinction exists to make, and the one a magnitude-only change could
        // not produce.
        composeTestRule.mainClock.advanceTimeBy(MID_DETONATION_MILLIS)
        composeTestRule.onRoot().captureRoboImage(BURST_REFERENCE)
    }

    private companion object {
        // The bare file name: Roborazzi resolves it against the module's reference directory,
        // and a path that repeated `src/test/screenshots` nested one inside the other.
        const val RAIN_REFERENCE = "celebration_mid_rain.png"
        const val BURST_REFERENCE = "celebration_mid_detonation.png"

        /**
         * Late in the first burst, chosen so the pieces are unmistakably *down* the banner.
         *
         * An instant nearer the start would look much like the bug it guards against — a few
         * dots near the top edge — and a reference that cannot tell the two apart is not a
         * guard. This is before the first crossing at ~880 ms, so it is still the opening burst.
         */
        const val MID_RAIN_MILLIS = 700L

        /**
         * Into the burst thrown by the *second* crossing, which is the one onto a milestone.
         *
         * Two crossings at roughly 880 ms each, plus a beat for the pieces to travel — chosen so
         * they are well clear of the centre and have not begun to fade.
         */
        const val MID_DETONATION_MILLIS = 2_000L
    }
}
