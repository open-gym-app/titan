package com.titan.core.ui.component

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.titan.core.ui.theme.MIN_TOUCH_TARGET
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The settings rows, as a screen reader meets them.
 *
 * The distinction under test is between a row that *is* a control and one that merely states
 * something: the first announces its value as its state and can be tapped, the second reads
 * as text and cannot. The Settings screen's "Font scale" row is the second kind and used to
 * be the easy thing to get wrong — see the issue that opened this file.
 */
@RunWith(RobolectricTestRunner::class)
class PreferenceRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a picker row announces its value as its state, once`() {
        var clicks = 0
        composeTestRule.setContent {
            TitanTheme { PreferenceRow(label = "Theme", value = "Auto", onClick = { clicks++ }) }
        }

        // "Auto, Theme, button": the value is the row's `stateDescription`, the way a switch
        // announces "on", and it is *not* also in the tree as text — or it would be said twice.
        composeTestRule
            .onNodeWithText("Theme")
            .assertIsDisplayed()
            .assert(hasStateDescription("Auto"))
            .assertHasClickAction()
            .performClick()
        composeTestRule.onNodeWithText("Auto").assertDoesNotExist()

        assertEquals(1, clicks)
    }

    @Test
    fun `an info row reads its value as text and offers no tap`() {
        composeTestRule.setContent {
            TitanTheme {
                PreferenceInfoRow(
                    label = "Font scale",
                    value = "System",
                    supporting = "Follows your device",
                )
            }
        }

        composeTestRule.onNodeWithText("Font scale").assertHasNoClickAction()
        composeTestRule.onNodeWithText("System").assertIsDisplayed()
    }

    @Test
    fun `a single-line row is still a whole tap target tall`() {
        composeTestRule.setContent {
            TitanTheme { PreferenceRow(label = "Units", value = "Metric", onClick = {}) }
        }

        // 43dp on its padding alone, which Accessibility Scanner reports as an error.
        composeTestRule.onNodeWithText("Units").assertHeightIsAtLeast(MIN_TOUCH_TARGET)
    }
}
