package com.titan.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The display names of the three stored preferences, on the same terms as `DomainLabelsTest`:
 * every value is walked rather than spot-checked, because the risk with a table of labels is
 * the value somebody added and did not label.
 *
 * They matter more than the other labels for one reason — two screens render them. Onboarding
 * asks for the goal, the level and the units on first launch and settings offers them all
 * afterwards, so a missing label is a blank row on the *first* screen a user ever sees.
 */
@RunWith(RobolectricTestRunner::class)
class PreferenceLabelsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `every theme has a name`() {
        assertAllLabelled(ThemePreference.entries) { listOf(it.label()) }
    }

    @Test
    fun `every unit system has a name and says which units it means`() {
        assertAllLabelled(UnitSystem.entries) { listOf(it.label(), it.detail()) }
    }

    @Test
    fun `every training goal has a name and says what training that way looks like`() {
        assertAllLabelled(TrainingGoal.entries) { listOf(it.label(), it.detail()) }
    }

    @Test
    fun `every fitness level has a name and says how long it means`() {
        assertAllLabelled(FitnessLevel.entries) { listOf(it.label(), it.detail()) }
    }

    @Test
    fun `every one-rep-max formula has a name and states its tradeoff`() {
        assertAllLabelled(OneRepMaxFormula.entries) { listOf(it.label(), it.detail()) }
    }

    @Test
    fun `only the automatic theme carries a detail`() {
        val details = mutableMapOf<ThemePreference, String?>()
        composeTestRule.setContent {
            TitanTheme {
                ThemePreference.entries.forEach { theme -> details[theme] = theme.detailOrNull() }
            }
        }

        // `Auto` is a promise that has to be spelled out; `Light` and `Dark` are what they say,
        // and a line under them would be filler in a picker of three.
        assertEquals("Follows your device", details[ThemePreference.SYSTEM])
        assertNull(details[ThemePreference.LIGHT])
        assertNull(details[ThemePreference.DARK])
    }

    /**
     * Renders every value's strings and asserts each one is non-blank and distinct.
     *
     * One `setContent` per test, taking every string a value carries in one pass — the rule
     * refuses a second call, and the label and its detail are the same question anyway.
     */
    private fun <T> assertAllLabelled(
        values: List<T>,
        strings: @Composable (T) -> List<String>,
    ) {
        val rendered = mutableListOf<String>()
        composeTestRule.setContent {
            TitanTheme {
                values.forEach { value -> rendered += strings(value) }
            }
        }

        assertEquals(emptyList<String>(), rendered.filter { it.isBlank() })
        assertEquals("labels must be distinct", rendered.size, rendered.toSet().size)
    }
}
