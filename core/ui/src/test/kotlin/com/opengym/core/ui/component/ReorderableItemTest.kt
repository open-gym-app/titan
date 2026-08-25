package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Picking an item up, moving it, and letting go.
 *
 * The rows here are plain boxes of a fixed height, which is the shape the arithmetic cares
 * about — where a row is and how tall it is. What a caller's rows *look* like, and what they
 * collapse to while one is held, is that caller's own test.
 *
 * ### The clock is the subject as much as the finger is
 *
 * A hold is a press that outlasts [DEFAULT_HOLD] without moving, so half of what these assert is
 * about time: a tap does nothing, a press cut short does nothing, and a press that waits picks
 * the item up. `advanceEventTime` is what moves both clocks — the one the events are stamped
 * with and the one the hold is counted on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ReorderableItemTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val moves = mutableListOf<Pair<Int, Int>>()
    private var commits = 0
    private var held: Any? = null
    private var reorder: ReorderState? = null

    @Test
    fun `the items are drawn in the order they are given`() {
        setList()

        composeTestRule.onNodeWithText("Bench press").assertExists()
        composeTestRule.onNodeWithText("Cable fly").assertExists()
    }

    @Test
    fun `a press that outlasts the hold picks the item up`() {
        setList()

        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            down(center)
            advanceEventTime(HELD_LONG_ENOUGH)
            moveBy(Offset.Zero)
        }
        composeTestRule.waitForIdle()

        assertEquals("Bench press", held)
    }

    @Test
    fun `a press let go before the hold picks nothing up`() {
        setList()

        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            down(center)
            advanceEventTime(TOO_SHORT)
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(null, held)
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `a press that moves before the hold is a scroll, not a pick-up`() {
        setList()

        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            down(center)
            advanceEventTime(TOO_SHORT)
            // Past the touch slop: whatever this was, it was not somebody holding still.
            moveBy(Offset(0f, ROW_HEIGHT.value * 2))
            advanceEventTime(HELD_LONG_ENOUGH)
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(null, held)
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `dragging a held item over its neighbour changes places with it`() {
        setList()

        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            down(center)
            advanceEventTime(HELD_LONG_ENOUGH)
            moveBy(Offset(0f, rowStep() * 0.6f))
            moveBy(Offset(0f, rowStep() * 0.6f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf(0 to 1), moves)
        assertEquals(1, commits)
    }

    @Test
    fun `dragging past two neighbours crosses both and saves once`() {
        setList()

        val row = rowStep()
        // In separate blocks, because a crossing is only decided once the layout it caused has
        // happened: on a device the frames between two touch events are what does that, and
        // here it is `waitForIdle`. A gesture carries on across the calls.
        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            down(center)
            advanceEventTime(HELD_LONG_ENOUGH)
            moveBy(Offset(0f, row * 1.2f))
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            moveBy(Offset(0f, row))
            up()
        }
        composeTestRule.waitForIdle()

        // What the user meant is where the finger came up, not each row it passed on the way.
        assertEquals(listOf(0 to 1, 1 to 2), moves)
        assertEquals(1, commits)
    }

    @Test
    fun `dragging upwards crosses the other way`() {
        setList()

        composeTestRule.onNodeWithText("Cable fly").performTouchInput {
            down(center)
            advanceEventTime(HELD_LONG_ENOUGH)
            moveBy(Offset(0f, -rowStep() * 0.6f))
            moveBy(Offset(0f, -rowStep() * 0.6f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf(2 to 1), moves)
    }

    @Test
    fun `a drag past the end of the list stops at the last slot`() {
        setList()

        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            down(center)
            advanceEventTime(HELD_LONG_ENOUGH)
            // Far past the bottom of the list, and then some: a finger is allowed to leave the
            // list, the exercise it is holding is not.
            moveBy(Offset(0f, rowStep() * 10))
        }
        composeTestRule.waitForIdle()

        // It went to the end, and it is drawn *in* the end slot rather than eight rows below
        // the last one.
        assertEquals(listOf(0 to 2), moves)
        assertEquals(0f, reorder!!.translation, 1f)
    }

    @Test
    fun `a drag past the top of the list stops at the first slot`() {
        setList()

        composeTestRule.onNodeWithText("Cable fly").performTouchInput {
            down(center)
            advanceEventTime(HELD_LONG_ENOUGH)
            moveBy(Offset(0f, -rowStep() * 10))
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf(2 to 0), moves)
        assertEquals(0f, reorder!!.translation, 1f)
    }

    @Test
    fun `letting go is the end of it`() {
        setList()

        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            down(center)
            advanceEventTime(HELD_LONG_ENOUGH)
            moveBy(Offset(0f, rowStep() * 1.1f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(null, held)
    }

    @Test
    fun `a hold that never moves saves nothing`() {
        setList()

        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            down(center)
            advanceEventTime(HELD_LONG_ENOUGH)
            up()
        }
        composeTestRule.waitForIdle()

        assertTrue(moves.isEmpty())
        assertEquals(0, commits)
    }

    @Test
    fun `a drag that wanders and comes back saves nothing`() {
        setList()

        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            down(center)
            advanceEventTime(HELD_LONG_ENOUGH)
            moveBy(Offset(0f, rowStep() * 0.3f))
            moveBy(Offset(0f, -rowStep() * 0.3f))
            up()
        }
        composeTestRule.waitForIdle()

        assertTrue(moves.isEmpty())
        assertEquals(0, commits)
    }

    @Test
    fun `every item offers the moves it has somewhere to make`() {
        setList()

        assertEquals(listOf("Move down"), items()[0].moveLabels())
        assertEquals(listOf("Move up", "Move down"), items()[1].moveLabels())
        assertEquals(listOf("Move up"), items()[2].moveLabels())
    }

    @Test
    fun `a move action reports the move and saves it in one go`() {
        setList()

        move(item = 1, label = "Move up")

        assertEquals(listOf(1 to 0), moves)
        assertEquals(1, commits)
    }

    @Test
    fun `an inert list can be neither held nor moved by an action`() {
        setList(enabled = false)

        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            down(center)
            advanceEventTime(HELD_LONG_ENOUGH)
            moveBy(Offset(0f, rowStep() * 1.1f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(null, held)
        assertTrue(moves.isEmpty())
        assertTrue(items().first().moveLabels().isEmpty())
    }

    @Test
    fun `holding an item is what tells the screen to change shape`() {
        setList()

        composeTestRule.onNodeWithText("Bench press").performTouchInput {
            down(center)
            advanceEventTime(HELD_LONG_ENOUGH)
            moveBy(Offset.Zero)
        }
        composeTestRule.waitForIdle()
        val whileHeld = held != null

        composeTestRule.onNodeWithText("Bench press").performTouchInput { up() }
        composeTestRule.waitForIdle()

        assertTrue(whileHeld)
        assertFalse(held != null)
    }

    // ─── Support ────────────────────────────────────────────────────────────────────────

    /**
     * Three rows of a fixed height, with the list hoisted the way a caller's is.
     *
     * The move is applied to it as it is reported, because that is the contract: the component
     * reads its own order back out of what it is given.
     */
    private fun setList(enabled: Boolean = true) {
        composeTestRule.setContent {
            OpenGymTheme {
                ReorderableList(enabled = enabled)
            }
        }
    }

    @Composable
    private fun ReorderableList(enabled: Boolean) {
        var items by remember { mutableStateOf(EXERCISES) }
        val state = rememberReorderState(
            itemCount = items.size,
            onMove = { from, to ->
                moves += from to to
                items = items.toMutableList().apply { add(to, removeAt(from)) }
            },
            onCommit = { commits++ },
            enabled = enabled,
        )
        held = state.heldKey
        reorder = state

        Column(modifier = Modifier.reorderable(state)) {
            items.forEachIndexed { index, name ->
                // Keyed, the way a `LazyColumn`'s items are: without it the composables stay
                // where they are and take on each other's data as the list reorders, which
                // restarts the gesture handler on the row a finger is holding.
                key(name) {
                    ReorderableItem(state = state, key = name, index = index) { isHeld ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ROW_HEIGHT)
                                .background(
                                    if (isHeld) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                ),
                        ) {
                            Text(text = name, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
            }
        }
    }

    /** The reorderable items, in the order they are drawn. */
    private fun items(): List<SemanticsNodeInteraction> {
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
        val count = composeTestRule.onAllNodes(matcher).fetchSemanticsNodes().size
        return (0 until count).map { composeTestRule.onAllNodes(matcher)[it] }
    }

    /** Performs one of an item's own move actions, the way an assistive technology would. */
    private fun move(item: Int, label: String) {
        val action = items()[item].moveActions().first { it.label == label }
        composeTestRule.runOnUiThread { action.action() }
        composeTestRule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.moveActions(): List<CustomAccessibilityAction> =
        fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }

    private fun SemanticsNodeInteraction.moveLabels(): List<String> =
        moveActions().map(CustomAccessibilityAction::label)

    /**
     * How far apart two rows' middles are, in the pixels a touch is expressed in.
     *
     * Measured off the item rather than off the text inside it: the text is twenty pixels tall
     * in a row of sixty-four, and a drag scaled to the wrong one of those crosses nothing.
     */
    private fun rowStep(): Float =
        items().first().fetchSemanticsNode().size.height.toFloat()

    private companion object {
        val EXERCISES = listOf("Bench press", "Incline dumbbell press", "Cable fly")
        val ROW_HEIGHT = 64.dp

        /** Past the hold, with room for the frame the timeout lands on. */
        const val HELD_LONG_ENOUGH = 1_400L

        /** A tap, in other words. */
        const val TOO_SHORT = 200L
    }
}
