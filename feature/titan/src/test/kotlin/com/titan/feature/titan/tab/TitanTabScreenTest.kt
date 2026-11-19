package com.titan.feature.titan.tab

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.titan.core.domain.gamification.PetNicknameResult
import com.titan.core.domain.model.CollectedItem
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class TitanTabScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<TitanTabEvent>()

    private fun show(state: TitanTabUiState) {
        composeTestRule.setContent {
            TitanTheme(reducedMotion = true) {
                TitanTabScreen(state, events::add, onOpenInventory = {})
            }
        }
    }

    @Test
    fun `the stage reads as the titan and what it is doing`() {
        show(previewTitan())

        composeTestRule.onNodeWithContentDescription("Brick, Squatting").assertExists()
    }

    @Test
    fun `each vital is read with its value, the low one as low`() {
        show(previewTitan())

        composeTestRule.onNodeWithContentDescription("Fuel, low, 22 of 100").performScrollTo()
        composeTestRule.onNodeWithContentDescription("Spirit, 55 of 100").performScrollTo()
    }

    @Test
    fun `a food tile feeds`() {
        show(previewTitan())

        composeTestRule.onNodeWithContentDescription("Steak, 1 left, New").performScrollTo().performClick()

        assertEquals(listOf<TitanTabEvent>(TitanTabEvent.Feed("steak")), events)
    }

    @Test
    fun `the rename dialog shows why a name is refused`() {
        show(previewTitan().copy(rename = TitanTabUiState.RenameState("x".repeat(25), PetNicknameResult.TooLong)))

        composeTestRule.onNodeWithText("At most 24 characters").assertExists()
    }

    @Test
    fun `with no titan the eggs can be hatched`() {
        show(
            TitanTabUiState.NoTitan(
                listOf(CollectedItem(3, "pet_egg", "session_finished", Instant.parse("2026-11-12T18:00:00Z"))),
            ),
        )

        composeTestRule.onNodeWithText("No titan yet").assertExists()
        composeTestRule.onNodeWithText("Hatch").performClick()

        assertEquals(listOf<TitanTabEvent>(TitanTabEvent.Hatch(3)), events)
    }
}
