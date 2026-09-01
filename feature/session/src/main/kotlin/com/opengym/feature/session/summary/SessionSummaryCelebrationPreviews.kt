package com.opengym.feature.session.summary

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.ui.theme.LocalReducedMotion

/*
 * The payoff, in its two shapes (#111).
 *
 * Both are captured under reduced motion, which is what makes them *references* rather than
 * frames: the count, the bar and the confetti all land immediately there, so a screenshot shows
 * the state the screen settles on instead of whichever millisecond the recorder happened to
 * catch. See `LocalReducedMotion`, and `ConfettiBurst` for why the burst is absent rather than
 * frozen.
 */

@Preview(name = "Session summary - xp earned", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenCelebrationPreview() {
    CompositionLocalProvider(LocalReducedMotion provides true) {
        PreviewScreen(previewState(celebration = previewCelebration()))
    }
}

@Preview(name = "Session summary - level up", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenLevelUpPreview() {
    CompositionLocalProvider(LocalReducedMotion provides true) {
        PreviewScreen(previewState(celebration = previewLevelUpCelebration()))
    }
}

@Preview(name = "Session summary - milestone", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenMilestonePreview() {
    CompositionLocalProvider(LocalReducedMotion provides true) {
        PreviewScreen(previewState(celebration = previewMilestoneCelebration()))
    }
}
