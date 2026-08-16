package com.opengym.core.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rename dialog, which three screens raise and none of them owns.
 *
 * ### Why the dialog is driven here rather than through the screens that raise it
 *
 * An `AlertDialog` composed over a whole screen does not reach idle under Robolectric — the
 * dialog and the screen behind it remeasure each other until the test JVM runs out of heap. It
 * is a harness pathology rather than a product one, and it is not worth contorting three screens
 * around: what each of *them* owes is that tapping the title emits `NameEditRequested`, which is
 * what their own tests assert. What the dialog does once it is up is one behaviour, tested once,
 * here.
 */
@RunWith(RobolectricTestRunner::class)
class SessionNameDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var name: String? = null
    private var confirmed = 0
    private var dismissed = 0

    @Test
    fun `the field opens on the name the session already has`() {
        setDialog("Push day · Upper")

        composeTestRule.onNodeWithText("Push day · Upper").assertIsDisplayed()
        composeTestRule.onNodeWithText("Name this workout").assertIsDisplayed()
    }

    @Test
    fun `an unnamed session opens on a placeholder rather than on nothing`() {
        setDialog("")

        composeTestRule.onNodeWithText("Push day · Upper").assertIsDisplayed()
    }

    @Test
    fun `typing reports what was typed`() {
        setDialog("Push")

        composeTestRule.onNodeWithContentDescription("Session name").performTextReplacement("Leg day")

        assertEquals("Leg day", name)
    }

    @Test
    fun `saving reports the confirmation`() {
        setDialog("Leg day")

        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun `an empty name can still be saved, because blank is how a session goes unnamed`() {
        setDialog("")

        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun `the hint says what an empty field will do, since the button does not go inert`() {
        setDialog("")

        composeTestRule.onNodeWithText("Leave it empty to go by the date instead.").assertIsDisplayed()
    }

    @Test
    fun `cancelling reports the dismissal and nothing else`() {
        setDialog("Leg day")

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    @Test
    fun `a write in flight leaves the dialog up and inert`() {
        // The text is the only copy of what the user wrote, so the field stays rather than
        // closing — and both buttons go quiet so a second press cannot race the first.
        setDialog("Leg day", isSaving = true)

        composeTestRule.onNodeWithText("Leg day").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    private fun setDialog(value: String, isSaving: Boolean = false) {
        composeTestRule.setContent {
            OpenGymTheme {
                SessionNameDialog(
                    name = value,
                    onNameChange = { name = it },
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                    isSaving = isSaving,
                )
            }
        }
    }
}
