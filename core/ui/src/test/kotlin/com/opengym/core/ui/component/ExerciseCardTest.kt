package com.opengym.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opengym.core.domain.model.ActiveSet
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The exercise row and card.
 *
 * The image is not asserted on: it is loaded asynchronously and there is nothing to load in a
 * JVM test, so what these check is the half that is always drawn — the name, the meta line
 * and the last-performed snippet. The letter under the photo is covered by the screenshots.
 */
@RunWith(RobolectricTestRunner::class)
class ExerciseCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val bench = Exercise(id = 1, name = "Barbell bench press", equipmentType = EquipmentType.BARBELL)

    private val links = listOf(
        link("TRICEPS", primary = false),
        link("CHEST", primary = true),
    )

    @Test
    fun `the meta line puts the equipment first and the prime mover ahead of the assistors`() {
        setContent {
            ExerciseListItem(exercise = bench, muscleGroups = links)
        }

        composeTestRule.onNodeWithText("Barbell bench press").assertIsDisplayed()
        composeTestRule.onNodeWithText("Barbell · Chest, Triceps").assertIsDisplayed()
    }

    @Test
    fun `an exercise with no equipment worth naming shows only its muscles`() {
        setContent {
            ExerciseListItem(
                exercise = Exercise(id = 2, name = "Farmer's carry", equipmentType = EquipmentType.OTHER),
                muscleGroups = listOf(link("FOREARMS", primary = true)),
            )
        }

        composeTestRule.onNodeWithText("Forearms").assertIsDisplayed()
    }

    @Test
    fun `a formatted snippet is shown as the last performance`() {
        setContent {
            ExerciseListItem(exercise = bench, lastPerformed = "Tue · 80 kg × 5")
        }

        composeTestRule.onNodeWithText("Last Tue · 80 kg × 5").assertIsDisplayed()
    }

    @Test
    fun `null means never done and says so`() {
        setContent { ExerciseListItem(exercise = bench, lastPerformed = null) }

        composeTestRule.onNodeWithText("Not logged yet").assertIsDisplayed()
    }

    @Test
    fun `negative - an empty snippet means the screen does not know, and draws no line`() {
        setContent { ExerciseListItem(exercise = bench, lastPerformed = "") }

        composeTestRule.onNodeWithText("Not logged yet").assertDoesNotExist()
    }

    @Test
    fun `a row with a click handler reports the tap`() {
        var clicks = 0
        setContent { ExerciseListItem(exercise = bench, onClick = { clicks++ }) }

        composeTestRule.onNodeWithText("Barbell bench press").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `a card draws whatever the screen puts under it`() {
        setContent {
            ExerciseCard(exercise = bench, muscleGroups = links, active = true) {
                LoggedSetRow(
                    setNumber = 1,
                    set = ActiveSet(reps = 10, weight = 60_000_000, isCompleted = true, completedAt = 1),
                )
            }
        }

        composeTestRule.onNodeWithText("Barbell bench press").assertIsDisplayed()
        composeTestRule.onNodeWithText("60 kg × 10").assertIsDisplayed()
    }

    private fun link(
        name: String,
        primary: Boolean,
    ) = ExerciseMuscleGroupLink(MuscleGroup(id = name.hashCode().toLong(), name = name), isPrimary = primary)

    private fun setContent(content: @Composable () -> Unit) {
        composeTestRule.setContent { OpenGymTheme(content = content) }
    }
}
