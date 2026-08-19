package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.SetType
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * What goes in a set row's leading cell: the set's number, or the initial of its type.
 *
 * One cell for both because they are the same fact — `W` *is* where the warm-up sits in the list
 * — and a row that drew a number *and* a badge would spend a second column saying so. The number
 * is the ordinary case and is drawn as plain text; a marked set gets its initial on a tinted
 * ground, which is what makes the warm-ups findable in a table of eight rows without reading any
 * of them.
 *
 * ### Why the tints are scheme roles rather than four colours
 *
 * The palette has one accent on purpose (CONTRIBUTING.md § Using the design system): a second hue
 * competing with it is how the accent stops meaning "this is the thing". Four saturated hues, one
 * per set type, is exactly the competition `PlateColors` already refused for plate sizes.
 *
 * So the four marked types are told apart by *temperature and weight*, out of roles the schemes
 * already define — a quiet fill, a warm taupe, a crimson, a cool stone — rather than by a bespoke
 * palette. Two of them take a *filled* neutral rather than its container tone, because four faint
 * fills a few percent apart is one treatment with four names. That is four unmistakably different
 * chips at 24dp, it inverts with the scheme for free, and every pair is one
 * `ColorSchemeContrastTest` already holds to WCAG AA.
 *
 * Which type gets which is not arbitrary either. Failure is the crimson, because a set taken to
 * failure is the one that changes how the rest of the exercise should be read; a warm-up is the
 * quietest, because it is the row a reader is usually skipping on the way to the working sets; and
 * drop sets and intervals take the warm and the cool neutral, which separates them from each other
 * and from the other two without either claiming to be urgent.
 *
 * @param setNumber the set's place in its exercise's table, from 1. Drawn only for
 *   [SetType.NORMAL] — every other type replaces it, which is the rule [SetType.markerOrNull]
 *   states.
 * @param isActive whether this is the row being worked, which tints the *number*. A marked set's
 *   chip carries its own ground and ignores it: the type of a set does not change because the
 *   cursor is in it, and a chip restated in the active row's colours would be a fifth tint that
 *   means nothing.
 */
@Composable
internal fun SetMarker(
    setNumber: Int,
    setType: SetType,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    if (setType == SetType.NORMAL) {
        Text(
            text = setNumber.toString(),
            style = OpenGymTheme.typography.numeric,
            color = if (isActive) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = modifier,
        )
        return
    }

    SetTypeChip(setType = setType, modifier = modifier)
}

/**
 * The tinted initial alone, without the number it stands in for.
 *
 * Drawn by [SetMarker] for a marked set, and by [SetTypePicker]'s menu against each type it
 * offers, so the chip a row will carry is the chip the user picked rather than a second rendering
 * of the same idea. [SetType.NORMAL] draws an empty square of the same size — the honest answer,
 * since an unmarked set has no mark, and one that collapsed would leave the menu's labels
 * hanging at two different indents.
 */
@Composable
internal fun SetTypeChip(setType: SetType, modifier: Modifier = Modifier) {
    val glyph = setType.markerOrNull()
    val tone = setType.markerTone()

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(MARKER_SIZE)
                .background(tone.container, MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center,
        ) {
            if (glyph != null) {
                Text(
                    text = glyph,
                    style = OpenGymTheme.typography.numeric,
                    color = tone.content,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The ground a set type's initial is drawn on, and the ink that reads on it.
 *
 * @property container the chip's fill.
 * @property content the initial itself. Always the `on` role of [container], so the pair is one
 *   the contrast test already covers rather than a combination invented here.
 */
@Immutable
internal data class SetTypeMarkerTone(val container: Color, val content: Color)

/**
 * Which tint a marked set carries. Exhaustive, so a value added to [SetType] is a compile error
 * here rather than an invisible chip on three screens.
 */
@Composable
@ReadOnlyComposable
internal fun SetType.markerTone(): SetTypeMarkerTone {
    val scheme = MaterialTheme.colorScheme

    return when (this) {
        // Nothing to tint: an unmarked set shows its number, and the empty square the menu draws
        // for it is empty on purpose.
        SetType.NORMAL -> SetTypeMarkerTone(Color.Transparent, scheme.onSurface)

        // The quietest of the four, and the faintest thing in the table that is still a chip: a
        // warm-up is the row a reader skips on the way to the working sets.
        SetType.WARMUP -> SetTypeMarkerTone(
            container = scheme.surfaceContainerHighest,
            content = scheme.onSurfaceVariant,
        )

        // The warm neutral at full strength — the accent's own hue drained of chroma. A container
        // tone was the first try and it lost: three faint fills a few percent apart is one
        // treatment with three names, and the letter was doing all the work.
        SetType.DROP_SET -> SetTypeMarkerTone(
            container = scheme.secondary,
            content = scheme.onSecondary,
        )

        // The error role, and the one type that gets it. Not a warning about a mistake — a set
        // taken to failure is a deliberate thing to do — but it *is* the row that changes how the
        // rest of the exercise should be read, which is the same "look here" the role carries.
        SetType.FAILURE -> SetTypeMarkerTone(
            container = scheme.errorContainer,
            content = scheme.onErrorContainer,
        )

        // The cool neutral at full strength, opposite the drop set's warm one. Two neutrals a
        // temperature apart is a distinction that survives being 24dp wide, where two hues of the
        // same lightness do not.
        SetType.INTERVAL -> SetTypeMarkerTone(
            container = scheme.tertiary,
            content = scheme.onTertiary,
        )
    }
}

/**
 * The chip's side.
 *
 * Square, and smaller than the cell it sits in, so the tint reads as a mark *on* the row rather
 * than as a cell of the table with a colour of its own.
 */
internal val MARKER_SIZE: Dp = 24.dp
