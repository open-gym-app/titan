package com.opengym.core.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import com.opengym.core.ui.R
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reordering a list by **picking one item up**: hold it, move it, let go.
 *
 * One gesture, start to finish. A press that stays still for [ReorderState.holdDuration] lifts
 * the item under the finger; from then on it follows the finger and changes places with whatever
 * it passes; letting go drops it where it is and is the only thing worth saving. Nothing is
 * entered and nothing is exited — there is no mode to leave, because the finger leaving *is* the
 * end of it.
 *
 * ### Why a hold rather than a handle
 *
 * A handle is a permanent control on every row, and the rows this was built for are the exercise
 * cards of a running workout: a set table, two text fields, a rating scale and two buttons each.
 * A grab handle on all of them is a column of chrome down a screen whose job is logging sets, and
 * a separate reorder *screen* is a mode to open, read, and close again for what is usually one
 * move. A hold costs nothing when nobody is holding.
 *
 * The cost is discoverability, and it is paid for twice: the list is expected to *change shape*
 * while an item is held — see [ReorderState.isActive] — which makes the pick-up unmistakable, and
 * a haptic tick fires the moment the item lifts.
 *
 * ### The caller owns the order
 *
 * [ReorderState.onMove] is called as the held item passes each neighbour and the caller applies
 * it to the list it is drawing; [ReorderState.onCommit] fires once, when the finger comes up.
 * Nothing here holds an order of its own — what it holds is where things *are*, measured, which
 * is a different question and the one the arithmetic needs.
 *
 * ### It survives the list changing under the finger
 *
 * Because it works in window coordinates. Every item reports its own bounds as it is laid out,
 * and the pointer is read in the same space, so a list that collapses its rows to a third of
 * their height mid-gesture — which is the whole point of the shape change above — re-measures and
 * carries on. An accumulated-offset implementation cannot do that: its arithmetic is relative to
 * a layout that no longer exists.
 *
 * ### What it does not do
 *
 * There is no auto-scroll at the edges. The list is expected to shrink to fit while an item is
 * held, which is what makes that unnecessary; a list that cannot do that wants a different
 * component.
 *
 * @param key what identifies this item across a reorder — the id of the row, never its index.
 * @param index where it currently sits, which is what [ReorderState.onMove] is expressed in.
 * @param content the item itself, told whether it is the one being held so it can be drawn as
 *   picked up — the component only lifts it above its neighbours and moves it, because what
 *   "picked up" looks like is the caller's design decision.
 */
@Composable
fun ReorderableItem(
    state: ReorderState,
    key: Any,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable (isHeld: Boolean) -> Unit,
) {
    val isHeld = state.heldKey == key
    val haptics = LocalHapticFeedback.current

    val moveUp = stringResource(R.string.reorder_move_up)
    val moveDown = stringResource(R.string.reorder_move_down)

    // Both ends of the gesture, under the thumb, for somebody looking at the exercise rather
    // than at the list: the tick that says the hold has taken, and the one that says the drop
    // landed. The second is the quieter of the two and is only fired for an item that was
    // actually held — every other row in the list also passes through `false` here, and a
    // list that ticked once per item on every drop would buzz for as long as the workout is.
    var wasHeld by remember { mutableStateOf(false) }
    LaunchedEffect(isHeld) {
        when {
            isHeld -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                wasHeld = true
            }

            wasHeld -> {
                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                wasHeld = false
            }
        }
    }

    // Its place goes with it. A list this long is a workout, and an exercise cut out of one
    // while nothing is held would otherwise leave a slot behind for the next drag to aim at.
    DisposableEffect(key) {
        onDispose { state.forget(key) }
    }

    Box(
        modifier = modifier
            // Over its neighbours while it is held. On the outer box, which is the one the
            // parent places: the transform below is on a child, so what is measured and
            // reported here stays the *slot* rather than where the finger has dragged it to.
            .zIndex(if (isHeld) 1f else 0f)
            .onGloballyPositioned {
                state.place(key = key, index = index, bounds = it.boundsInWindow())
            }
            .semantics {
                customActions = buildList {
                    if (!state.isEnabled) return@buildList
                    if (index > 0) add(CustomAccessibilityAction(moveUp) { state.step(index, -1) })
                    if (index < state.lastIndex) {
                        add(CustomAccessibilityAction(moveDown) { state.step(index, 1) })
                    }
                }
            },
    ) {
        Box(modifier = Modifier.graphicsLayer { translationY = if (isHeld) state.translation else 0f }) {
            content(isHeld)
        }
    }
}

/**
 * The gesture, on the **list** rather than on the items in it.
 *
 * On the container because the items move: a reorder takes the row under the finger and puts it
 * somewhere else in the list, and a gesture handler living on that row is detached and restarted
 * as it goes — one crossing in, mid-drag, with the finger still down. The list itself never
 * moves, so the handler on it survives the thing it is causing.
 *
 * Which item was pressed is answered from the same measurements everything else here uses: the
 * items report where they are, and the press names a point. A press that lands between two of
 * them picks up neither.
 */
fun Modifier.reorderable(state: ReorderState): Modifier =
    this
        .onGloballyPositioned { state.container = it }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!state.isEnabled) return@awaitEachGesture
                if (!awaitHold(down, state.holdDuration)) return@awaitEachGesture

                val container = state.container ?: return@awaitEachGesture
                val pressedAt = container.localToWindow(down.position).y
                val key = state.keyAt(pressedAt) ?: return@awaitEachGesture
                state.lift(key, pressedAt)

                dragHeld(pointer = down.id, container = container, state = state)
                state.drop()
            }
        }

/**
 * Where the reorder is: which item is held, where the finger is, and where everything sits.
 *
 * Hoisted at the screen rather than owned by the list, because what an item being held *means*
 * is the screen's business — it is what collapses a workout to its titles, and the component
 * that draws one row cannot do that.
 *
 * @param onMove the item at `from` should now be at `to`. Called as the held item passes a
 *   neighbour, and once per accessibility action; the caller applies it to the list it draws.
 * @param onCommit the finger came up on an order that is not the one it went down on. The one
 *   thing worth writing: a drag across four rows is four moves and one new order.
 * @param isEnabled false makes the list inert: nothing can be picked up, and the move actions go
 *   with it. Both, because they are one control offered two ways.
 */
@Stable
class ReorderState internal constructor(
    internal val holdDuration: Duration,
    private var onMove: (from: Int, to: Int) -> Unit,
    private var onCommit: () -> Unit,
    internal var isEnabled: Boolean = true,
    internal var itemCount: Int = 0,
) {

    /** The list's own coordinates, which is what a pointer is read against. */
    internal var container: LayoutCoordinates? = null

    /** The item under the finger, or null when nobody is holding one. */
    var heldKey: Any? by mutableStateOf(null)
        private set

    /**
     * Whether an item is being held.
     *
     * The signal a screen changes shape on: while this is true the list is expected to draw
     * itself compactly, so that what is being dragged and where it can go both fit on one
     * screen. See [ReorderableItem] for why that is a requirement rather than a flourish.
     */
    val isActive: Boolean get() = heldKey != null

    /** Where each item sits, keyed the way the caller keys them. */
    private val placements = mutableStateMapOf<Any, Placement>()

    /** Where the finger is, in window coordinates. */
    private var pointerY by mutableFloatStateOf(0f)

    /**
     * The gap the finger keeps from the middle of the row it is holding.
     *
     * What stops the row teleporting when the list changes shape. A press lands somewhere in a
     * card that is hundreds of pixels tall; the moment it lifts, every card on the screen
     * collapses to a line, and the finger is then nowhere near the middle of anything. Measuring
     * that gap once the new layout has been laid out — and holding it — means the row starts
     * exactly where it is drawn and moves with the finger from there, rather than jumping to it
     * and taking the order with it.
     */
    private var grab by mutableFloatStateOf(0f)

    /**
     * Whether [grab] has been fixed, which happens the first time the finger actually moves.
     *
     * Until then every re-measure re-takes it, which is what absorbs the collapse — and any
     * other relayout that happens between the press and the first movement.
     */
    private var grabbed = false

    /**
     * Where the held row's middle should be: the finger, less the gap it keeps from it, and
     * never outside the list.
     *
     * Confined rather than free, which is what keeps a drag on rails. The finger has the whole
     * screen — the header, the status bar, the space under the last exercise — and a row that
     * followed it there would be an exercise floating over the toolbar, or off the top edge
     * entirely, with nothing under it to drop onto. There is nowhere above the first row or
     * below the last for an exercise to go, so there is nowhere up there for it to be drawn:
     * past either end the row sits at that end and waits, and the finger can carry on as far
     * as it likes without taking the workout with it.
     */
    private val virtualY: Float get() = confine(pointerY - grab)

    /**
     * Keeps a row's middle inside the stretch the list actually occupies.
     *
     * The lane is the rows themselves, first top to last bottom, less half the held row at each
     * end so that it stops flush with them rather than half over the edge. Measured every time
     * rather than taken once: the list changes shape under the finger, and a lane from before
     * the collapse describes a screen that is no longer there.
     */
    private fun confine(centre: Float): Float {
        val held = placements[heldKey] ?: return centre
        val half = (held.bottom - held.top) / 2f
        val top = placements.values.minOf { it.top } + half
        val bottom = placements.values.maxOf { it.bottom } - half
        // A list of one, or one row taller than everything else put together: there is only one
        // place it can be, and `coerceIn` on a backwards range throws.
        if (bottom < top) return (top + bottom) / 2f
        return centre.coerceIn(top, bottom)
    }

    /**
     * The move that has been reported and not yet drawn.
     *
     * A crossing is decided from measured bounds, and those bounds are a frame old the moment
     * [onMove] is called. Without this latch the same crossing is decided again against the
     * layout it has already changed, and one drag past one neighbour reports four moves.
     */
    private var awaiting: Int? = null

    /** Whether this gesture has changed the order, which is whether it is worth saving. */
    private var moved = false

    /**
     * The last index in the *list*, which is not the last one measured.
     *
     * Told rather than counted, because a lazy list only ever places what is on screen: an
     * exercise at the bottom of the window would otherwise be offered no "move down", on the
     * grounds that nothing below it had been composed yet.
     */
    internal val lastIndex: Int get() = itemCount - 1

    /**
     * How far the held item has to be drawn from its slot to sit where the finger has put it.
     *
     * Bounded by [virtualY]: at the ends of the list this settles to zero, so a finger that
     * keeps going drags nothing further and the row rests in the last slot there is.
     */
    internal val translation: Float
        get() = virtualY - (placements[heldKey]?.centre ?: virtualY)

    internal fun place(
        key: Any,
        index: Int,
        bounds: androidx.compose.ui.geometry.Rect,
    ) {
        val placement = Placement(index = index, top = bounds.top, bottom = bounds.bottom)
        placements[key] = placement
        if (key != heldKey) return

        if (index == awaiting) awaiting = null
        // Re-taken until the finger moves, so the row sits still through the shape change the
        // pick-up causes — see [grab].
        if (!grabbed) grab = pointerY - placement.centre
    }

    internal fun lift(
        key: Any,
        pointerWindowY: Float,
    ) {
        heldKey = key
        pointerY = pointerWindowY
        grab = pointerWindowY - (placements[key]?.centre ?: pointerWindowY)
        grabbed = false
        awaiting = null
        moved = false
    }

    /**
     * Moves the finger, changing places with a neighbour once it has passed the middle of one.
     *
     * The target is read off the *measured* rows rather than accumulated from the drag, which
     * is what lets the list collapse under the finger mid-gesture: both the pointer and the
     * rows are in window coordinates, so a re-measure is picked up rather than fought.
     */
    internal fun dragTo(pointerWindowY: Float) {
        pointerY = pointerWindowY
        grabbed = true

        val held = heldKey
        // Nothing to decide: no item is held, a crossing is already reported and waiting to be
        // drawn, or the held row has not been measured since it was picked up.
        val slot = if (held == null || awaiting != null) null else placements[held]
        if (slot == null) return
        val from = slot.index

        val target = virtualY
        // Which way the row is being carried, which is what settles a dead heat. [confine] parks
        // it flush with the first or last slot, and flush is *exactly* level with the centre of
        // the row that lives there — so at both ends of the list the comparison below is a tie,
        // and the tie has to break the way the finger is going. Strict either way and one end of
        // the workout becomes a place a drag can approach and never reach.
        val goingDown = target > slot.centre

        val to = placements
            .filterKeys { it != held }
            .count { (_, placement) ->
                if (goingDown) placement.centre <= target else placement.centre < target
            }
            .coerceIn(0, lastIndex.coerceAtLeast(0))

        if (to != from) {
            awaiting = to
            moved = true
            onMove(from, to)
        }
    }

    internal fun drop() {
        val worthSaving = moved
        heldKey = null
        awaiting = null
        moved = false
        grabbed = false
        if (worthSaving) onCommit()
    }

    /**
     * The accessibility actions' move: one place, applied and saved in the same breath.
     *
     * A drag is a gesture an assistive technology cannot perform, so the same two moves are
     * always reachable as actions on the item. There is no gesture around them to end, which is
     * why this commits as it goes.
     *
     * @return true always, which is how an accessibility action says it handled the request.
     *   They are only offered where there is somewhere to move to.
     */
    internal fun step(
        from: Int,
        by: Int,
    ): Boolean {
        onMove(from, from + by)
        onCommit()
        return true
    }

    /** Which item covers [windowY], or null for a press that landed between two of them. */
    internal fun keyAt(windowY: Float): Any? =
        placements.entries
            .firstOrNull { (_, placement) -> windowY >= placement.top && windowY <= placement.bottom }
            ?.key

    /** Forgets an item that has left the list, so nothing aims a drop at where it used to be. */
    internal fun forget(key: Any) {
        placements.remove(key)
    }

    /** Keeps the callbacks, the count and the enablement current, without making them part of
     *  this object's identity. */
    internal fun update(
        onMove: (Int, Int) -> Unit,
        onCommit: () -> Unit,
        isEnabled: Boolean,
        itemCount: Int,
    ) {
        this.onMove = onMove
        this.onCommit = onCommit
        this.isEnabled = isEnabled
        this.itemCount = itemCount
    }

    /** Where one item is, in window coordinates. */
    private data class Placement(
        val index: Int,
        val top: Float,
        val bottom: Float,
    ) {
        val centre: Float get() = (top + bottom) / 2f
    }
}

/**
 * A [ReorderState] for one list, remembered across recompositions.
 *
 * @param itemCount how many items the list holds — all of them, not the ones on screen. See
 *   [ReorderState.lastIndex].
 * @param enabled false makes the whole list inert — a workout that is paused, or one exercise
 *   long.
 * @param holdDuration how long the finger has to stay still before the item lifts. A little
 *   longer than the platform's own long-press by default: the rows this was built for are full
 *   of things to press, and a reorder that started as easily as a menu does would fire on
 *   somebody steadying their thumb to type a weight. Only a little, though — see [DEFAULT_HOLD].
 */
@Composable
fun rememberReorderState(
    itemCount: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onCommit: () -> Unit,
    enabled: Boolean = true,
    holdDuration: Duration = DEFAULT_HOLD,
): ReorderState {
    val state = remember(holdDuration) { ReorderState(holdDuration, onMove, onCommit) }
    // Refreshed rather than captured: the lambdas close over this composition's state, and a
    // gesture that started three recompositions ago must still write to the current one.
    state.update(
        onMove = onMove,
        onCommit = onCommit,
        isEnabled = enabled,
        itemCount = itemCount,
    )
    return state
}

/**
 * Long enough not to fire on a steadying thumb, short enough not to feel broken.
 *
 * 700ms, against the platform's 500: it was 1.2s, which is a *long* time to hold a phone still
 * with nothing on screen acknowledging it, and long enough that somebody who has not been told
 * the gesture exists lets go before it fires. The guard against an accidental lift is mostly
 * [awaitHold]'s slop check rather than the clock — a finger on its way to a text field moves —
 * so the clock only has to outlast a deliberate press, and 200ms over the platform does that.
 */
val DEFAULT_HOLD: Duration = 700.milliseconds

/**
 * Follows the held item until the finger comes up.
 *
 * On the **initial** pass, and consuming there, which is the one thing this could not be written
 * without: the list underneath is a scrolling one, and a scroll container is a *descendant* of
 * the modifier this runs on — so on the ordinary pass it sees every drag first, takes it for a
 * scroll, and the drag ends one movement after it began. Claiming the event before it is offered
 * to anything inside is what makes a reorder possible on a list that also scrolls.
 *
 * The hold before it deliberately does not do this. Until an item is picked up, a finger that
 * moves is somebody scrolling, and the list is entitled to it.
 */
private suspend fun AwaitPointerEventScope.dragHeld(
    pointer: PointerId,
    container: LayoutCoordinates,
    state: ReorderState,
) {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == pointer } ?: return
        if (!change.pressed) return
        if (change.positionChange() == Offset.Zero) continue

        state.dragTo(container.localToWindow(change.position).y)
        change.consume()
    }
}

/**
 * Whether the finger stayed put long enough to be a hold.
 *
 * Aborted by a lift and by any movement past the touch slop, and both are what makes this
 * cohabit with a scrolling list: until the hold takes, nothing is consumed, so a drag that was
 * meant to scroll scrolls.
 */
private suspend fun AwaitPointerEventScope.awaitHold(
    down: PointerInputChange,
    holdDuration: Duration,
): Boolean =
    try {
        withTimeout(holdDuration.inWholeMilliseconds) {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) return@withTimeout false
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    return@withTimeout false
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        // Nothing happened for long enough, which is exactly what a hold is.
        true
    }

/**
 * The grab glyph: a hint that a row is one of the ones that move.
 *
 * Not a control — the whole row is what responds to a hold — so it carries no description of its
 * own; the item it sits on is what announces the two moves. Drawn quietly, since it is on every
 * row of the list and none of them is about it.
 */
@Composable
fun DragHandle(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Icon(
        imageVector = Icons.Filled.DragIndicator,
        contentDescription = null,
        tint = tint,
        modifier = modifier,
    )
}
