package com.opengym.core.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The naming dialog three screens raise and none of them owns: "save as template" on the recap,
 * rename and duplicate on the templates tab, and the editor's own header.
 *
 * Driven directly rather than through any of them, for the reason [SessionNameDialogTest] gives:
 * what each screen owes is that its own control emits the event that opens this, which is what
 * their tests assert. What the dialog does once it is up is one behaviour, tested once, here.
 */
@RunWith(RobolectricTestRunner::class)
class TemplateNameDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var name: String? = null
    private var category: TemplateCategory? = null
    private var categoryChanges = 0
    private var confirmed = 0
    private var dismissed = 0

    @Test
    fun `it opens on the name it was given, under the title it was given`() {
        setDialog("Push Day · Upper", title = "Save as template")

        composeTestRule.onNodeWithText("Push Day · Upper").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save as template").assertIsDisplayed()
    }

    @Test
    fun `typing reports what was typed`() {
        setDialog("Push")

        composeTestRule.onNodeWithContentDescription("Template name")
            .performTextReplacement("Push Day · Heavy")

        assertEquals("Push Day · Heavy", name)
    }

    @Test
    fun `a blank name cannot be confirmed, because a template must have one`() {
        // Unlike a session's, whose empty field is how it goes back to being untitled.
        setDialog("")

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `confirming reports it`() {
        setDialog("Push Day")

        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals(1, confirmed)
    }

    @Test
    fun `cancelling reports the dismissal and nothing else`() {
        setDialog("Push Day")

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    // ─── The category ───────────────────────────────────────────────────────────────────

    @Test
    fun `the category it was given is the one selected`() {
        setDialog("Push Day", category = TemplateCategory.STRENGTH)

        composeTestRule.onNodeWithText("STRENGTH").assertIsSelected()
    }

    @Test
    fun `picking a category reports it`() {
        setDialog("Push Day", category = TemplateCategory.STRENGTH)

        composeTestRule.onNodeWithText("MOBILITY").performClick()

        assertEquals(TemplateCategory.MOBILITY, category)
    }

    @Test
    fun `None is a chip, so a template can be un-categorised`() {
        // Null is not GENERAL — see TemplateCategory — so the absence has to be selectable, or
        // categorising one by mistake would be permanent.
        setDialog("Push Day", category = TemplateCategory.STRENGTH)

        composeTestRule.onNodeWithText("NONE").performClick()

        assertNull(category)
        assertEquals(1, categoryChanges)
    }

    @Test
    fun `no category selects the None chip rather than nothing`() {
        setDialog("Push Day", category = null)

        composeTestRule.onNodeWithText("NONE").assertIsSelected()
    }

    // ─── Refusals and writes in flight ──────────────────────────────────────────────────

    @Test
    fun `a name already in use is drawn under the field, with the name still in it`() {
        setDialog("Pull Day", error = "You already have a template with that name")

        composeTestRule.onNodeWithText("You already have a template with that name")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Pull Day").assertIsDisplayed()
        // Still confirmable: the fix is a word, and the button going inert would say otherwise.
        composeTestRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun `a write in flight leaves the dialog up and inert`() {
        setDialog("Push Day", isSaving = true)

        composeTestRule.onNodeWithText("Push Day").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun `the body says what is being saved, where the caller has something to say`() {
        setDialog("Push Day", body = "The exercises you performed, in order.")

        composeTestRule.onNodeWithText("The exercises you performed, in order.").assertIsDisplayed()
    }

    @Suppress("LongParameterList")
    private fun setDialog(
        value: String,
        title: String = "Save as template",
        category: TemplateCategory? = null,
        body: String? = null,
        error: String? = null,
        isSaving: Boolean = false,
    ) {
        this.category = category
        composeTestRule.setContent {
            OpenGymTheme {
                TemplateNameDialog(
                    name = value,
                    onNameChange = { name = it },
                    onConfirm = { confirmed++ },
                    onDismiss = { dismissed++ },
                    title = title,
                    confirmLabel = "Save",
                    category = category,
                    onCategoryChange = {
                        this.category = it
                        categoryChanges++
                    },
                    body = body,
                    error = error,
                    isSaving = isSaving,
                )
            }
        }
    }
}
