package com.titan.core.ui.component

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The letter under the photo.
 *
 * The image itself has nothing to load in a JVM test, so what is checked here is the fallback
 * and what it does to the semantics tree — the two things the screenshots cannot see, since a
 * reference image says nothing about what TalkBack reads.
 */
@RunWith(RobolectricTestRunner::class)
class ExerciseImageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the initial is the first letter of the name, uppercased`() {
        assertEquals("B", exercise("barbell bench press").initial())
    }

    @Test
    fun `a name that is only whitespace still draws something`() {
        assertEquals("?", exercise("   ").initial())
    }

    @Test
    fun `an emoji initial is taken whole rather than split down the middle`() {
        val initial = exercise("🏋️ Bench").initial()

        assertTrue("expected a weightlifter, got $initial", initial.startsWith("🏋"))
        assertFalse(
            "a trailing high surrogate is half a character, and renders as tofu",
            Character.isHighSurrogate(initial.last()),
        )
    }

    @Test
    fun `negative - the fallback letter is not read out ahead of the exercise name`() {
        composeTestRule.setContent {
            TitanTheme {
                ExerciseListItem(
                    exercise = exercise("Barbell bench press"),
                    onClick = {},
                )
            }
        }

        // The row merges its descendants, so an uncleared letter would be announced as
        // "B, Barbell bench press, Barbell" — and would stay there once the photo loaded
        // over it, since nothing removes it.
        composeTestRule.onNodeWithText("B").assertDoesNotExist()
    }

    private fun exercise(name: String) = Exercise(id = 1, name = name, equipmentType = EquipmentType.BARBELL)
}
