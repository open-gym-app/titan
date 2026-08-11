package com.opengym.core.ui.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.SetType
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The display names of the domain enums.
 *
 * They are composables because they read string resources, so this is a Compose test that
 * renders the label into a `Text` and reads it back — the cheapest way to assert a
 * `stringResource` without standing up a `Context` by hand.
 *
 * Every enum is walked exhaustively rather than spot-checked. The whole risk with a table of
 * labels is the value somebody added and did not label, and a test that checks three of
 * seventeen does not catch it.
 */
@RunWith(RobolectricTestRunner::class)
class DomainLabelsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `every set type has a name`() {
        assertAllLabelled(SetType.entries) { it.label() }
    }

    @Test
    fun `every personal record type has a name`() {
        assertAllLabelled(PrType.entries) { it.label() }
    }

    @Test
    fun `every exercise type has a name`() {
        assertAllLabelled(ExerciseType.entries) { it.label() }
    }

    @Test
    fun `every equipment type but OTHER has a name`() {
        val labelled = EquipmentType.entries - EquipmentType.OTHER
        assertAllLabelled(labelled) { requireNotNull(it.labelOrNull()) }
    }

    @Test
    fun `negative - OTHER has no label, because it would say nothing`() {
        renderAll(listOf(EquipmentType.OTHER)) { it.labelOrNull() ?: NOTHING }

        composeTestRule.onNodeWithText(NOTHING).assertIsDisplayed()
    }

    @Test
    fun `only a normal set is drawn as its number`() {
        val markers = mutableMapOf<SetType, String?>()
        composeTestRule.setContent {
            OpenGymTheme {
                SetType.entries.forEach { type -> markers[type] = type.markerOrNull() }
            }
        }

        assertEquals(null, markers[SetType.NORMAL])
        assertEquals("W", markers[SetType.WARMUP])
        assertEquals("D", markers[SetType.DROP_SET])
        assertEquals("F", markers[SetType.FAILURE])
        assertEquals("I", markers[SetType.INTERVAL])
    }

    @Test
    fun `a seeded muscle group is shown under its own name, not the seed's key`() {
        renderAll(listOf(MuscleGroup(id = 1, name = "QUADRICEPS"))) { it.displayName() }

        composeTestRule.onNodeWithText("Quads").assertIsDisplayed()
    }

    @Test
    fun `a user-authored group is tidied rather than shouted`() {
        renderAll(listOf(MuscleGroup(id = 1, name = "REAR_DELTS"))) { it.displayName() }

        composeTestRule.onNodeWithText("Rear delts").assertIsDisplayed()
    }

    @Test
    fun `a group already in sentence case is left alone`() {
        assertEquals("Rear delts", MuscleGroup(id = 1, name = "Rear delts").prettifiedName())
        assertEquals("?", MuscleGroup(id = 1, name = "?").prettifiedName())
    }

    /** Renders every value's label and asserts each one is a non-blank, distinct string. */
    private fun <T> assertAllLabelled(values: List<T>, label: @Composable (T) -> String) {
        val labels = mutableListOf<String>()
        composeTestRule.setContent {
            OpenGymTheme {
                values.forEach { value -> labels += label(value) }
            }
        }

        assertEquals(emptyList<String>(), labels.filter { it.isBlank() })
        assertEquals("labels must be distinct", labels.size, labels.toSet().size)
    }

    private fun <T> renderAll(values: List<T>, label: @Composable (T) -> String) {
        composeTestRule.setContent {
            OpenGymTheme {
                values.forEach { value -> Text(text = label(value)) }
            }
        }
    }

    private companion object {
        const val NOTHING = "(no label)"
    }
}
