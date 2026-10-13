package com.titan.core.ui.component

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.titan.core.ui.theme.TitanTheme
import kotlin.math.roundToInt

/**
 * A row that is thrown away by dragging it a good way to the left and letting go.
 *
 * ### Why a gesture rather than a control
 *
 * Because of where the rows are. A logged set is a line in a table that is read at a glance
 * between sets, and the thing a lifter does to that table most often is *nothing* — a control on
 * every line of it is chrome on every line of it, and a destructive control on every line is one
 * that gets pressed by the thumb steadying the phone. A long drag along the row is hard to make
 * by accident: it is one direction, one continuous movement, with the finger down the whole way.
 *
 * That is also why a drag the other way does nothing here. One direction means one outcome, and a
 * gesture that deletes one way and does something else the other is a gesture nobody performs
 * confidently over rows they care about.
 *
 * ### Why the gesture is drawn here rather than taken from `SwipeToDismissBox`
 *
 * Material's box is the obvious answer and it commits on the wrong thing. It settles on distance
 * *or velocity*, so a quick horizontal flick a third of the way across deletes the row — which is
 * exactly the accident this is supposed to be immune to, and the reason its default threshold is
 * half the width rather than all of it. Here the only thing that commits is distance:
 * [COMMIT_FRACTION] of the row, released. A row let go short of that springs back, however fast it
 * was travelling.
 *
 * The track under the finger says which side of that line the drag is on — the soft error
 * container while it would spring back, the full error tone once it would commit — so the
 * threshold is visible rather than something to learn by losing a set.
 *
 * ### It always springs back
 *
 * Even on a commit. What removes the row from the screen is the *data*: [onDelete] starts a write,
 * the store re-emits without the row, and the row goes with it. A row left parked off-screen would
 * be a second answer to "is this row still here" — one that says no over a delete that was
 * refused, leaving an invisible row the table still counts.
 *
 * ### TalkBack gets an action, because it cannot swipe
 *
 * The same rule `ReorderableItem` follows for a drag: a gesture is not an affordance to a screen
 * reader, so what the gesture does is also offered as a custom action on the row. A row that can
 * only be deleted by dragging it is a row a TalkBack user cannot delete.
 *
 * ### Keying
 *
 * The drag's position is remembered by *slot*, so a caller drawing these in a `Column` should wrap
 * each one in `key(id) { }` — without it, a row leaving the list hands its half-finished gesture to
 * whichever row moves up into its place.
 *
 * @param onDelete throw this row away. Called once as the finger comes up on a completed drag, and
 *   once per invocation of the accessibility action. The caller is expected to make it reversible
 *   rather than to confirm it — see `DeleteSetUseCase` for why an undo beats a dialog in front of a
 *   frequent action.
 * @param actionLabel what that accessibility action is called, e.g. "Delete set 3". Named by the
 *   caller because only the caller knows what the row is; a component-level "Delete" would be five
 *   identical actions in a table of five sets.
 * @param rowBackground what the row is drawn on, painted under [content] so the track does not
 *   show through it while it slides. Defaults to the card level, which is where set rows live —
 *   the rows themselves are deliberately transparent, since a table of opaque rows is a table with
 *   no card under it.
 * @param enabled false takes the gesture away and leaves the row exactly as it looks — a paused or
 *   finished workout, whose deletions the domain refuses anyway.
 */
@Composable
fun SwipeToDelete(
    onDelete: () -> Unit,
    actionLabel: String,
    modifier: Modifier = Modifier,
    rowBackground: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // Plain state written straight from the drag callback rather than an `Animatable` snapped to in
    // a coroutine: the position has to be current *inside the same gesture*, and a launch is a
    // frame later — which on release is the difference between reading where the finger let go and
    // reading where it was when the last frame was drawn.
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }

    val isCommitted = width > 0 && -offset >= width * COMMIT_FRACTION

    val deleteAction = CustomAccessibilityAction(actionLabel) {
        onDelete()
        true
    }

    Box(
        modifier = modifier
            .onSizeChanged { width = it.width }
            .semantics { customActions = listOf(deleteAction) },
    ) {
        DeleteTrack(isCommitted = isCommitted, modifier = Modifier.matchParentSize())

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.roundToInt(), 0) }
                .background(rowBackground)
                .draggable(
                    state = rememberDraggableState { delta ->
                        // Clamped to the row: there is nothing to the right of where it started,
                        // and dragging past the left edge would let the commit fraction be reached
                        // by a gesture that had already run out of row.
                        offset = (offset + delta).coerceIn(-width.toFloat(), 0f)
                    },
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    onDragStopped = {
                        // Recomputed from where the finger actually let go rather than read off
                        // the last composition, which is a frame behind a fast gesture.
                        if (width > 0 && -offset >= width * COMMIT_FRACTION) onDelete()
                        animate(initialValue = offset, targetValue = 0f) { value, _ ->
                            offset = value
                        }
                    },
                ),
            content = { content() },
        )
    }
}

/**
 * What is under the row while it is being dragged: the error tone, and what will happen.
 *
 * Two strengths of it. The soft container while the drag would still spring back, and the full
 * error tone once it has gone far enough to commit — so the gesture reports its own threshold, and
 * a user who has dragged far enough can see that they have without letting go to find out.
 */
@Composable
private fun DeleteTrack(
    isCommitted: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .background(if (isCommitted) scheme.error else scheme.errorContainer)
            .padding(horizontal = TitanTheme.spacing.xxl),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            // The row itself carries the accessibility action; this is the picture of it.
            contentDescription = null,
            tint = if (isCommitted) scheme.onError else scheme.onErrorContainer,
        )
    }
}

/**
 * How much of the row has to have gone before letting go deletes it.
 *
 * Under half, because the row is as wide as the phone: a threshold near the far edge asks the
 * thumb to cross the whole screen, which is a two-handed gesture on a phone held in one hand
 * between sets. Two fifths is still well past a flick along the row or a mis-aimed scroll — and
 * velocity never counts, so a fast short drag springs back however hard it was thrown. What makes
 * the shorter distance safe is that the delete is undoable rather than confirmed (see [onDelete]),
 * so the gesture only has to rule out the accident, not the change of mind.
 */
private const val COMMIT_FRACTION = 0.4f
