package com.titan.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The corner radii.
 *
 * The wireframes use three — 4 for an inline fill or a thumbnail, 6 for a card or a button,
 * 8 for anything that reads as a surface of its own. They are small on purpose: a tight
 * radius reads as an instrument, a large one reads as a toy, and this app is meant to look
 * like the former to somebody checking it between sets.
 *
 * [Shapes.large] and [Shapes.extraLarge] carry no wireframe value because nothing in the
 * wireframes is that big. They are set anyway because Material components claim them —
 * `ModalBottomSheet` takes `extraLarge` for its top corners, dialogs take `large` — and a
 * slot left at the Material default (16dp and 28dp) is where the rounded-toy look gets back
 * in through a component nobody styled.
 */
internal val TitanShapes = Shapes(
    // Inline fills, skeletons, image thumbnails.
    extraSmall = RoundedCornerShape(4.dp),
    // Buttons and the standard card.
    small = RoundedCornerShape(6.dp),
    // A surface holding other surfaces: a stat grid, a card with rows inside it.
    medium = RoundedCornerShape(8.dp),
    // Dialogs.
    large = RoundedCornerShape(12.dp),
    // Bottom sheets — the rest timer, the exercise picker.
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * A fully rounded shape, for chips and filter pills.
 *
 * Not a [Shapes] slot: Material's shape scale is a scale of *radii*, and this is not a
 * radius — it tracks the height of whatever it wraps. Expressed as a percentage so it stays
 * a capsule at any font scale.
 */
val PillShape = RoundedCornerShape(percent = 50)
