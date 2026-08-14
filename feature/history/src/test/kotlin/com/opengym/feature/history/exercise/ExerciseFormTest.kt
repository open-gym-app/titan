package com.opengym.feature.history.exercise

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.library.CHEST
import com.opengym.feature.history.library.TAXONOMY
import com.opengym.feature.history.library.TRICEPS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The custom-exercise form.
 *
 * Driven directly rather than through the `ModalBottomSheet` it normally lives in: the sheet
 * contributes an animation and a window, and neither is what any of this is about. The screen
 * test covers the one thing the sheet is responsible for — that `+ NEW` opens it.
 */
@RunWith(RobolectricTestRunner::class)
class ExerciseFormTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val drafts = mutableListOf<ExerciseDraft>()
    private var submits = 0

    private fun setContent(draft: ExerciseDraft = ExerciseDraft()) {
        composeTestRule.setContent {
            OpenGymTheme {
                ExerciseForm(
                    draft = draft,
                    muscleGroups = TAXONOMY,
                    onDraftChange = drafts::add,
                    onSubmit = { submits++ },
                    onCancel = {},
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        composeTestRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text))
    }

    @Test
    fun `typing a name reports the whole draft back`() {
        setContent()

        composeTestRule.onNodeWithText("Name").performTextInput("Zercher squat")

        assertEquals(listOf("Zercher squat"), drafts.map { it.name })
    }

    @Test
    fun `picking a primary muscle reports it`() {
        setContent(ExerciseDraft(name = "Zercher squat"))

        scrollTo("PRIMARY MUSCLE")
        composeTestRule.onAllNodesWithText("CHEST").onFirst().performClick()

        assertEquals(listOf(CHEST.id), drafts.map { it.primaryMuscleGroupId })
    }

    @Test
    fun `picking the prime mover takes it out of the secondaries`() {
        setContent(
            ExerciseDraft(name = "Dip", secondaryMuscleGroupIds = setOf(CHEST.id, TRICEPS.id)),
        )

        scrollTo("PRIMARY MUSCLE")
        composeTestRule.onAllNodesWithText("CHEST").onFirst().performClick()

        // A group cannot be both, and a link table with a duplicate key would reject the
        // write for a reason the user could not see.
        val reported = drafts.single()
        assertEquals(CHEST.id, reported.primaryMuscleGroupId)
        assertEquals(setOf(TRICEPS.id), reported.secondaryMuscleGroupIds)
    }

    @Test
    fun `choosing equipment reports it`() {
        setContent(ExerciseDraft(name = "Zercher squat"))

        scrollTo("EQUIPMENT")
        composeTestRule.onNodeWithText("MACHINE").performClick()

        assertEquals(listOf(EquipmentType.MACHINE), drafts.map { it.equipment })
    }

    @Test
    fun `a duplicate name is said under the field, and blocks the save`() {
        setContent(
            ExerciseDraft(name = "Bench press", primaryMuscleGroupId = CHEST.id, isDuplicateName = true),
        )

        composeTestRule
            .onNodeWithText("You already have this exercise with this equipment")
            .assertIsDisplayed()
        scrollTo("Create")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun `a nameless draft cannot be saved`() {
        setContent(ExerciseDraft(primaryMuscleGroupId = CHEST.id))

        scrollTo("Create")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun `a draft with no prime mover cannot be saved`() {
        setContent(ExerciseDraft(name = "Zercher squat"))

        scrollTo("Create")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun `a complete draft can be saved`() {
        setContent(ExerciseDraft(name = "Zercher squat", primaryMuscleGroupId = CHEST.id))

        scrollTo("Create")
        composeTestRule.onNodeWithText("Create").assertIsEnabled()
        composeTestRule.onNodeWithText("Create").performClick()

        assertEquals(1, submits)
    }

    @Test
    fun `a save in flight makes the form inert`() {
        setContent(
            ExerciseDraft(name = "Zercher squat", primaryMuscleGroupId = CHEST.id, isSaving = true),
        )

        scrollTo("Create")
        composeTestRule.onNodeWithText("Create").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun `the prime mover is not offered again as a secondary`() {
        setContent(ExerciseDraft(name = "Dip", primaryMuscleGroupId = CHEST.id))

        scrollTo("ALSO WORKS")

        // CHEST appears once — in the primary row — rather than in both.
        assertTrue(composeTestRule.onAllNodesWithText("CHEST").fetchSemanticsNodes().size == 1)
    }
}
