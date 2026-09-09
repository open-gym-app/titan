package com.opengym.core.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatCount
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * One item of a list that is being reordered: its place, what it is, and a word about it.
 *
 * Every card of a workout — or of a template's plan — shrinks to one of these the moment an
 * exercise is picked up, and that is the point of them rather than a side effect. A workout is
 * eight cards and a thousand pixels of set tables; dragging the sixth exercise above the second
 * in that layout means dragging into a screen edge and waiting for it to scroll. Collapsed, the
 * whole session is one screen, and the move is one movement of the thumb. It is also what makes
 * [ReorderableItem]'s "no auto-scroll at the edges" affordable.
 *
 * What is left is what the decision needs: where the exercise is in the order, what it is, and
 * whatever one fact distinguishes it. Every control is gone for the duration — nothing on them
 * can be used while a finger is holding an item, and each one drawn is a row to drag past.
 *
 * @param position where it sits in the order **as currently dragged**, from 0. Not the stored
 *   position: while a drag is in flight the two differ, and the number a lifter is looking at has
 *   to be the one they are creating.
 * @param name what the item is. The one thing that is always drawn.
 * @param isHeld whether this is the item under the finger. Drawn as the one accented card on the
 *   screen, which is what "picked up" looks like here — the accent is spent on the thing that
 *   matters right now, and while a drag is in flight nothing else does.
 *
 *   It is also the one row at full strength: it lifts a little, casts a shadow, and everything
 *   it is being dragged past steps back to [PASSING_ALPHA]. The accent alone carries that on a
 *   pale screen and almost nothing on a dark one, where a border two shades off the card is the
 *   difference between held and not. Depth reads in both, and it says the right thing — the row
 *   is off the page, in the hand, and the rest of the list is what it is being moved through.
 *
 *   Sprung rather than eased, and the one place in the app that bounces: a pick-up is a physical
 *   event and it is the only feedback the gesture gets besides the haptic tick under the thumb.
 * @param meta one short fact, drawn in the numeric role before the badge: how many sets have been
 *   logged against it, what its plan says. Null where there is nothing worth saying — "0 sets"
 *   against every exercise nobody has reached yet is a column of noise down a list whose subject
 *   is the order.
 * @param supersetLabel the `A1`/`A2` marker, or null. The grouping survives a reorder (or is torn
 *   by it), so it is the one badge worth keeping while the list is collapsed.
 */
@Composable
fun CollapsedReorderRow(
    position: Int,
    name: String,
    modifier: Modifier = Modifier,
    isHeld: Boolean = false,
    meta: String? = null,
    supersetLabel: String? = null,
) {
    // Resolved before the semantics block, which is not a composable context.
    val description = stringResource(R.string.reorder_position, position + 1, name)

    val scale by animateFloatAsState(
        targetValue = if (isHeld) HELD_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "held scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isHeld) 1f else PASSING_ALPHA,
        label = "passing alpha",
    )
    val elevation by animateDpAsState(
        targetValue = if (isHeld) HELD_ELEVATION else 0.dp,
        label = "held elevation",
    )
    val shape = MaterialTheme.shapes.small

    OpenGymCard(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                shadowElevation = elevation.toPx()
                this.shape = shape
                clip = false
            }
            // One node, read as one line: "3. Cable fly". The parts of the row — a handle, a
            // number, a name, a count — are four stops in the traversal order and none of them
            // means anything on its own, and the moves themselves are offered as actions on the
            // item this sits in. See [ReorderableItem].
            .clearAndSetSemantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        accented = isHeld,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = OpenGymTheme.spacing.lg,
                    vertical = OpenGymTheme.spacing.md,
                ),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DragHandle(
                modifier = Modifier.size(HANDLE_SIZE),
                tint = if (isHeld) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            )
            Text(
                text = formatCount(position + 1),
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (meta != null) {
                Text(
                    text = meta,
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (supersetLabel != null) SupersetBadge(label = supersetLabel)
        }
    }
}

/** Matches the glyph size the set rows use, so the column of handles lines up with the numbers. */
private val HANDLE_SIZE: Dp = 18.dp

/*
 * How far off the page the held row comes. Small numbers on purpose: the row is one line tall
 * and forty of them can be on screen at once, so a lift big enough to be theatrical is one that
 * overlaps its neighbours and makes the order harder to read — which is the one thing the user
 * is looking at.
 */
private const val HELD_SCALE = 1.04f
private val HELD_ELEVATION: Dp = 10.dp

/** How much of itself a row the held one is being dragged past keeps. */
private const val PASSING_ALPHA = 0.72f
