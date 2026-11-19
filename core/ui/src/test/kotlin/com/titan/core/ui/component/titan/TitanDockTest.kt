package com.titan.core.ui.component.titan

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanVital
import com.titan.core.domain.model.TitanVitals
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TitanDockTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var opened = 0

    private fun show(
        activity: TitanActivity = TitanActivity.RUN,
        reducedMotion: Boolean = true,
    ) {
        composeTestRule.setContent {
            TitanTheme(reducedMotion = reducedMotion) {
                TitanDock(
                    header = "Brick · Lvl 14",
                    status = "On a run",
                    activity = activity,
                    vitals = TitanVitals(fuel = 22, spirit = 55),
                    alert = TitanVital.FUEL,
                    head = null,
                    face = null,
                    contentDescription = SPOKEN,
                    onClick = { opened++ },
                )
            }
        }
    }

    @Test
    fun `the dock is one button that reads the titan, its activity and its alert, and opens it`() {
        show()

        composeTestRule.onAllNodesWithContentDescription(SPOKEN).assertCountEquals(1)
        composeTestRule.onNodeWithContentDescription(SPOKEN)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        composeTestRule.onNodeWithText("On a run").assertDoesNotExist()
        assertEquals(1, opened)
    }

    @Test
    fun `a roaming titan does not stop the test clock from settling once motion is reduced`() {
        show(TitanActivity.SWIM, reducedMotion = true)

        composeTestRule.onNodeWithContentDescription(SPOKEN).assertExists()
    }

    private companion object {
        const val SPOKEN = "Brick: On a run. Fuel low. Open titan."
    }
}
