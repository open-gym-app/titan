package com.opengym.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.opengym.core.ui.theme.HAIRLINE_SOFT_ALPHA
import com.opengym.core.ui.theme.HAIRLINE_WIDTH

/**
 * The wireframes' box: a surface a step above the page, bounded by a hairline rather than by
 * a shadow.
 *
 * Not Material's `Card`, which is elevation-first — its default is a tonal surface with a
 * shadow and no border, and reaching the look below through `CardDefaults` means every call
 * site restating four arguments and one of them eventually differing. This design separates
 * surfaces with a line everywhere (see `Hairline.kt`), so the line is the default and there
 * is nothing to restate.
 *
 * @param shape defaults to `shapes.medium`, the radius the wireframes give a surface holding
 *   other content. Pass `shapes.small` for a card that is a single control.
 * @param accented draws the border in the accent instead of the hairline shade, for the one
 *   card on a screen that is *live* — the exercise being worked, the session that can be
 *   resumed. The fill does not change: an accent-filled card would take the accent away from
 *   the button inside it, which is the thing the user is meant to press. There should never
 *   be two of these on one screen; a second one makes both of them ordinary.
 */
@Composable
fun OpenGymCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    accented: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = HAIRLINE_WIDTH,
            color = if (accented) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        content = { Column(content = content) },
    )
}

/**
 * The separator *between two rows inside* an [OpenGymCard] — the softer of the two hairline
 * shades, so a card's own border still reads as the stronger line.
 *
 * Full-bleed rather than inset. An inset divider implies the rows either side are grouped
 * under something, and in a settings list or a session's set list they are simply peers.
 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = HAIRLINE_WIDTH,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = HAIRLINE_SOFT_ALPHA),
    )
}
