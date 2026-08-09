package com.opengym.core.ui.format

import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The weight formatter, in a pinned locale.
 *
 * [Locale.setDefault] in the harness, not because the formatter should be locale-blind — it
 * should not; a French user gets a decimal comma and that is correct — but because a test
 * that asserted on `22.5` while CI ran under `fr-FR` would fail for the one reason that is
 * not a bug. The locale-sensitivity itself is asserted at the bottom.
 */
class WeightFormatTest {

    @Test
    fun `an olympic bar is twenty kilos, or forty-four pounds`() {
        withLocale(Locale.UK) {
            // The default bar weight is the value users see most and the one the plate
            // calculator works up from, so it is the value worth pinning by name.
            assertEquals("20 kg", formatWeight(UserPreferences.DEFAULT_BAR_WEIGHT, UnitSystem.METRIC))
            assertEquals("44.1 lb", formatWeight(UserPreferences.DEFAULT_BAR_WEIGHT, UnitSystem.IMPERIAL))
        }
    }

    @Test
    fun `a whole weight loses its trailing zero`() {
        withLocale(Locale.UK) {
            assertEquals("100", formatWeightValue(100_000_000, UnitSystem.METRIC))
            assertEquals("0", formatWeightValue(0, UnitSystem.METRIC))
        }
    }

    @Test
    fun `a fractional weight keeps one decimal and no more`() {
        withLocale(Locale.UK) {
            // 2.5 kg is the smallest plate most racks own; 22.75 rounds rather than truncating.
            assertEquals("2.5", formatWeightValue(2_500_000, UnitSystem.METRIC))
            assertEquals("22.8", formatWeightValue(22_750_000, UnitSystem.METRIC))
            assertEquals("22.7", formatWeightValue(22_740_000, UnitSystem.METRIC))
        }
    }

    @Test
    fun `a weight that rounds up to a whole number loses its decimal too`() {
        withLocale(Locale.UK) {
            // 99.98 kg must read as `100`, not as `100.0`: the rounding happens first and the
            // "is it whole" question is asked of the result, not of the input.
            assertEquals("100", formatWeightValue(99_980_000, UnitSystem.METRIC))
        }
    }

    @Test
    fun `the suffix names the unit and nothing else`() {
        assertEquals("kg", UnitSystem.METRIC.weightSuffix)
        assertEquals("lb", UnitSystem.IMPERIAL.weightSuffix)
    }

    @Test
    fun `typing a weight back in round-trips through milligrams`() {
        // The bar-weight setting is a text field over a stored `Long`, so an edit that changed
        // nothing must not move the value — otherwise opening and closing the dialog drifts it.
        listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL).forEach { units ->
            withLocale(Locale.UK) {
                val milligrams = UserPreferences.DEFAULT_BAR_WEIGHT
                val shown = formatWeightValue(milligrams, units).toDouble()
                val stored = weightToMilligrams(shown, units)

                assertEquals(formatWeightValue(milligrams, units), formatWeightValue(stored, units))
            }
        }
    }

    @Test
    fun `a pound is the international pound, not an approximation of one`() {
        // 453.59237 g exactly. A rounded constant drifts by a plate over a heavy squat.
        assertEquals(453_592.37, MILLIGRAMS_PER_POUND, 0.0)
        assertEquals(1_000_000.0, MILLIGRAMS_PER_KILOGRAM, 0.0)
    }

    @Test
    fun `the decimal separator follows the device locale`() {
        withLocale(Locale.FRANCE) {
            assertEquals("2,5", formatWeightValue(2_500_000, UnitSystem.METRIC))
        }
    }

    private fun withLocale(locale: Locale, block: () -> Unit) {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
