package com.opengym.core.ui.format

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The one line a set is written on, in the four shapes a set can take.
 *
 * A composable rather than a plain function, and therefore a composable test: the unit comes
 * from `LocalUnitSystem` and the "×" from the resources, which is the whole reason this is not
 * assembled in a ViewModel.
 *
 * The locale is pinned for the reason every formatter test pins it — see [withLocale].
 */
@RunWith(RobolectricTestRunner::class)
class SetFormatTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a weighted set reads as its weight by its reps`() = withLocale(Locale.UK) {
        setLine(weightMilligrams = 80_000_000L, reps = 5)

        composeTestRule.onNodeWithText("80 kg × 5").assertIsDisplayed()
    }

    @Test
    fun `a bodyweight set is its reps alone`() = withLocale(Locale.UK) {
        // Pull-ups, dips, push-ups: the movement has no weight to name, and `0 kg × 12` would
        // be the line claiming one.
        setLine(weightMilligrams = null, reps = 12)

        composeTestRule.onNodeWithText("12 reps").assertIsDisplayed()
    }

    @Test
    fun `a set with a weight and no reps is the weight alone`() = withLocale(Locale.UK) {
        setLine(weightMilligrams = 60_000_000L, reps = null)

        composeTestRule.onNodeWithText("60 kg").assertIsDisplayed()
    }

    @Test
    fun `a set with neither has no line at all`() = withLocale(Locale.UK) {
        // Null rather than a dash, so the caller decides: a table draws a placeholder to keep
        // its columns lined up, and a sentence quoting the set leaves the clause out.
        setLine(weightMilligrams = null, reps = null)

        assertFalse(composeTestRule.onRoot().printToString().contains("Text"))
    }

    private fun setLine(weightMilligrams: Long?, reps: Int?) {
        composeTestRule.setContent {
            OpenGymTheme {
                formatSet(weightMilligrams, reps)?.let { Text(text = it) }
            }
        }
    }
}
