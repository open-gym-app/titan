package com.titan.feature.titan.tab

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.titan.core.domain.gamification.PetNicknameResult
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.TitanActivity
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
    private var sparOpened = 0

    private fun show(state: TitanTabUiState) {
        composeTestRule.setContent {
            TitanTheme(reducedMotion = true) {
                TitanTabScreen(state, events::add, onOpenInventory = {}, onOpenSpar = { sparOpened++ })
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
        composeTestRule.onNodeWithContentDescription("Energy, 80 of 100").performScrollTo()
    }

    @Test
    fun `choosing an option sends it, and spar opens the spar screen instead`() {
        show(previewTitan())

        composeTestRule.onNodeWithContentDescription("Walk, costs 5 energy").performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Spar, costs 20 energy").performScrollTo().performClick()

        assertEquals(listOf<TitanTabEvent>(TitanTabEvent.Choose(TitanActivity.WALK)), events)
        assertEquals(1, sparOpened)
    }

    @Test
    fun `an unaffordable option does nothing and says why`() {
        show(previewTitan().let { it.copy(vitals = it.vitals.copy(energy = 4)) })

        composeTestRule.onNodeWithContentDescription("Run, needs 15 energy").performScrollTo().assertIsNotEnabled()
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
