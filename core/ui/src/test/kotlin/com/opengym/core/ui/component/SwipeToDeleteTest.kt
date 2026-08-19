package com.opengym.core.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The drag that deletes a row, and everything that is deliberately not it.
 *
 * The point of the component is the *threshold*, so that is what most of this is about: a
 * destructive action reached by a gesture is only safe if the gesture is one nobody performs by
 * accident, and a half-swipe let go is the accident.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class SwipeToDeleteTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var deletes = 0

    @Test
    fun `a drag across the whole row deletes it`() {
        swipeToDelete()

        composeTestRule.onNodeWithTag(ROW).performTouchInput {
            down(centerRight)
            moveTo(centerLeft)
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(1, deletes)
    }

    @Test
    fun `negative - a drag let go half way deletes nothing`() {
        swipeToDelete()

        composeTestRule.onNodeWithTag(ROW).performTouchInput {
            down(centerRight)
            moveTo(center)
            up()
        }

        assertEquals(0, deletes)
    }

    @Test
    fun `negative - a flick is not a drag across the row`() {
        swipeToDelete()

        // Material's own `SwipeToDismissBox` settles on distance *or velocity*, which is why this
        // does not use it: a fast flick a third of the way across is exactly the accident the
        // gesture is supposed to be immune to.
        composeTestRule.onNodeWithTag(ROW).performTouchInput {
            swipeLeft(startX = right, endX = right - (right - left) / 3f, durationMillis = 50)
        }

        assertEquals(0, deletes)
    }

    @Test
    fun `negative - a drag the other way deletes nothing`() {
        swipeToDelete()

        composeTestRule.onNodeWithTag(ROW).performTouchInput {
            down(centerLeft)
            moveTo(centerRight)
            up()
        }

        assertEquals(0, deletes)
    }

    @Test
    fun `negative - a row that cannot be written to does not move`() {
        swipeToDelete(enabled = false)

        composeTestRule.onNodeWithTag(ROW).performTouchInput {
            down(centerRight)
            moveTo(centerLeft)
            up()
        }

        assertEquals(0, deletes)
    }

    @Test
    fun `the same thing is offered as an action, because TalkBack cannot swipe`() {
        swipeToDelete()

        val action = composeTestRule.onNodeWithTag(ROW)
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .single()

        assertEquals("Delete set 3", action.label)
        action.action()
        composeTestRule.waitForIdle()

        assertEquals(1, deletes)
    }

    private fun swipeToDelete(enabled: Boolean = true) {
        composeTestRule.setContent {
            OpenGymTheme {
                SwipeToDelete(
                    onDelete = { deletes++ },
                    actionLabel = "Delete set 3",
                    modifier = Modifier.testTag(ROW).fillMaxWidth(),
                    enabled = enabled,
                ) {
                    Text(text = "80 kg × 5", modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    private companion object {
        const val ROW = "row"
    }
}
