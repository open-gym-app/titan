package com.titan.feature.session.summary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.session.summary.component.AchievementsUnlockedCard

/*
 * The payoff, in its two shapes (#111).
 *
 * Both are captured under reduced motion, which is what makes them *references* rather than
 * frames: the count, the bar and the confetti all land immediately there, so a screenshot shows
 * the state the screen settles on instead of whichever millisecond the recorder happened to
 * catch. See `LocalReducedMotion`, and `ConfettiBurst` for why the burst is absent rather than
 * frozen.
 *
 * Reduced motion is passed to `TitanTheme` rather than provided around it: the theme provides
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

@Preview(name = "Session summary - streak saved", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenStreakSavedPreview() {
    PreviewScreen(
        previewState(celebration = previewCelebration(), streakSave = previewStreakSave()),
        reducedMotion = true,
    )
}

/*
 * The save with no XP beside it.
 *
 * Deliberate: a save with no celebration above it is a real session — the streak step
 * is not fenced by the XP claim, so a re-entered cascade can report a protected day and nothing
 * else. It is also the only preview where this card is the first thing under the stat grid,
 * which is the layout worth having a reference for.
 */
@Preview(name = "Session summary - streak saved, none left", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun SessionSummaryScreenStreakSpentPreview() {
    PreviewScreen(previewState(streakSave = previewStreakSave()), reducedMotion = true)
}

/*
 * The milestone card on its own, at both metals it can draw.
 *
 * The three screens above capture the whole recap, where this card sits below the fold — so the
 * medals a session actually pays out had no reference image at all until this one. Two rows, on
 * two different ladders, which is what puts a second metal and a second glyph in the frame.
 */

@Preview(name = "Milestones unlocked", showBackground = true)
@Composable
fun AchievementsUnlockedCardPreview() {
    TitanTheme(theme = ThemePreference.LIGHT, reducedMotion = true) {
        UnlockedCard()
    }
}

@Preview(name = "Milestones unlocked - dark", showBackground = true)
@Composable
fun AchievementsUnlockedCardDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK, reducedMotion = true) {
        UnlockedCard()
    }
}

@Composable
private fun UnlockedCard() {
    Box(modifier = Modifier.padding(TitanTheme.spacing.screen)) {
        AchievementsUnlockedCard(
            achievements = listOf(
                SummaryAchievement(id = "four_week_streak", xp = 100),
                SummaryAchievement(id = "hundred_sessions", xp = 100),
            ),
        )
    }
}
