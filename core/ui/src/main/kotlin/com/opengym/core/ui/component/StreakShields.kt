package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * What is left of a fixed monthly allowance, drawn as one mark per charge.
 *
 * Built for streak protection — three a month, spent automatically when a missed day would
 * otherwise break a streak worth keeping (see `StreakProtection`) — and it is the surface the
 * quest log and the just-finished recap both draw. Kept in `:core:ui` rather than in whichever
 * of them shipped first for exactly that reason.
 *
 * ### Marks, not a number
 *
 * `2 / 3` is a smaller thing on the screen and a worse answer. An allowance is countable and
 * *tiny*, and three marks with one greyed out says "you have used one of your three" in the
 * time it takes to glance, without the reader parsing a fraction. It is the same argument
 * [StepProgress] makes for segments over a filled rail, applied to a resource rather than to a
 * position — and it is why this takes the two counts rather than a proportion: at three
 * charges, a percentage is a rounding of a number the user can see.
 *
 * The spent marks stay on screen rather than disappearing. A row that shrank as protections
 * were used would be a different shape every month and would never say what the allowance
 * *is*, which is half of what somebody looking at it wants to know.
 *
 * ### One accent, spent on the charges that are left
 *
 * The held marks take `primary` and the spent ones `outlineVariant`. This is the app's single
 * accent doing what it is for — the thing that matters is the resource still in hand — and it
 * is the reason there is no third state and no "warning" tone at one remaining: a second hue
 * would make the row a status light rather than a count. See `OpenGymPlateColors` for the
 * long-form argument the rest of the system is built on.
 *
 * ### It speaks once, or not at all
 *
 * The row's contents are replaced wholesale, always: the marks are `Icon`s and a screen reader
 * handed three of them reads three shields. What replaces them is [contentDescription], which
 * has to carry both counts and the word for what they are — "two of three streak protections
 * left this month" — none of which is in this component; the caller has the string resources.
 *
 * **Null** is the other legitimate answer, and it means "the thing beside me already said it".
 * A card that draws the marks next to the sentence that counts them must pass null, or a reader
 * hears the same fact twice. It is null rather than an empty string so that saying nothing is a
 * decision in the source rather than a value that looks like an oversight.
 *
 * @param remaining how many charges are still in hand. Clamped into `0..allowance`, so a
 *   caller that has read a stale count draws a full or an empty row rather than a row of the
 *   wrong length.
 * @param allowance how many the period holds — the number of marks drawn. Must be positive.
 * @param contentDescription the whole sentence a screen reader hears in place of the marks, or
 *   **null** where the caller's own text already says it.
 * @param markSize a mark's size. The default suits a card's body text; it is a parameter only
 *   so a denser host can ask for less.
 */
@Composable
fun StreakShields(
    remaining: Int,
    allowance: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    markSize: Dp = SHIELD_MARK_SIZE,
) {
    require(allowance > 0) { "an allowance has at least one charge, was $allowance" }

    val held = remaining.coerceIn(0, allowance)

    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription?.let { this.contentDescription = it }
        },
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(allowance) { index ->
            val isHeld = index < held
            Icon(
                // Filled against outlined, not only accent against grey: the two states have to
                // survive a colour-blind reader and a greyscale screenshot, which is the same
                // reason `SetTypeMarker` carries a letter rather than a tint alone.
                imageVector = if (isHeld) Icons.Filled.Shield else Icons.Outlined.Shield,
                contentDescription = null,
                tint = if (isHeld) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                modifier = Modifier.size(markSize),
            )
        }
    }
}

/**
 * A mark's drawn size.
 *
 * Sixteen device-independent pixels: the same optical weight as the body text it sits beside,
 * so the row reads as part of the sentence rather than as an icon strip above it.
 */
val SHIELD_MARK_SIZE: Dp = 16.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Streak shields", widthDp = 360)
@Composable
fun StreakShieldsPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        StreakShieldsCatalogue()
    }
}

@Preview(name = "Streak shields, dark", widthDp = 360)
@Composable
fun StreakShieldsDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        StreakShieldsCatalogue()
    }
}

/** Every state an allowance of three can be in, full to empty. */
@Composable
private fun StreakShieldsCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        for (remaining in PREVIEW_ALLOWANCE downTo 0) {
            StreakShields(
                remaining = remaining,
                allowance = PREVIEW_ALLOWANCE,
                contentDescription = "$remaining of $PREVIEW_ALLOWANCE left",
            )
        }
    }
}

private const val PREVIEW_ALLOWANCE = 3
