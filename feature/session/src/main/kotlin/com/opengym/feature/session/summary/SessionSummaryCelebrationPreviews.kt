package com.opengym.feature.session.summary

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/*
 * The payoff, in its two shapes (#111).
 *
 * Both are captured under reduced motion, which is what makes them *references* rather than
 * frames: the count, the bar and the confetti all land immediately there, so a screenshot shows
 * the state the screen settles on instead of whichever millisecond the recorder happened to
 * catch. See `LocalReducedMotion`, and `ConfettiBurst` for why the burst is absent rather than
 * frozen.
 *
 * Reduced motion is passed to `OpenGymTheme` rather than provided around it: the theme provides
 * `LocalReducedMotion` itself, so a `CompositionLocalProvider` on the outside would be shadowed
 * and these would quietly capture the animation instead.
 */

@Preview(name = "Session summary - xp earned", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenCelebrationPreview() {
    PreviewScreen(previewState(celebration = previewCelebration()), reducedMotion = true)
}

@Preview(name = "Session summary - level up", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenLevelUpPreview() {
    PreviewScreen(previewState(celebration = previewLevelUpCelebration()), reducedMotion = true)
}

@Preview(name = "Session summary - milestone", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenMilestonePreview() {
    PreviewScreen(previewState(celebration = previewMilestoneCelebration()), reducedMotion = true)
}
