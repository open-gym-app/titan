package com.opengym.core.ui.format

import com.opengym.core.domain.model.MILLIGRAMS_PER_KILOGRAM
import com.opengym.core.domain.model.MILLIGRAMS_PER_POUND
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.validation.SetValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The weight formatter, in a pinned locale.
 *
 * [withLocale] in the harness, not because the formatter should be locale-blind — it should
 * not; a French user gets a decimal comma and that is correct — but because a test that
 * asserted on `22.5` while CI ran under `fr-FR` would fail for the one reason that is not a
 * bug. The locale-sensitivity itself is asserted at the bottom.
 */
@RunWith(RobolectricTestRunner::class)
class WeightFormatTest {

    @Test
    fun `an olympic bar is twenty kilos, or forty-four pounds`() =
        withLocale(Locale.UK) {
            // The default bar weight is the value users see most and the one the plate
            // calculator works up from, so it is the value worth pinning by name.
            assertEquals("20 kg", formatWeight(UserPreferences.DEFAULT_BAR_WEIGHT, UnitSystem.METRIC))
            assertEquals("44.09 lb", formatWeight(UserPreferences.DEFAULT_BAR_WEIGHT, UnitSystem.IMPERIAL))
        }

    @Test
    fun `a whole weight loses its trailing zero`() =
        withLocale(Locale.UK) {
            assertEquals("100", formatWeightValue(100_000_000, UnitSystem.METRIC))
            assertEquals("0", formatWeightValue(0, UnitSystem.METRIC))
            // -0.0 is what a plain format makes of a value rounded down to nothing from below.
            assertEquals("0", formatWeightValue(-1, UnitSystem.METRIC))
        }

    @Test
    fun `a fractional weight keeps two decimals and no more`() =
        withLocale(Locale.UK) {
            assertEquals("2.5", formatWeightValue(2_500_000, UnitSystem.METRIC))
            assertEquals("22.75", formatWeightValue(22_750_000, UnitSystem.METRIC))
            assertEquals("22.74", formatWeightValue(22_740_000, UnitSystem.METRIC))
            assertEquals("22.75", formatWeightValue(22_745_000, UnitSystem.METRIC))
        }

    @Test
    fun `a projection is rounded to the whole unit`() =
        withLocale(Locale.UK) {
            // The same milligrams, through the two formatters. A lift keeps the precision that
            // lets it be reproduced on a rack; an estimated one-rep max is a formula's output
            // over a top set and was never racked, so the decimals would claim an accuracy the
            // projection does not have.
            assertEquals("22.75", formatWeightValue(22_750_000, UnitSystem.METRIC))
            assertEquals("23", formatEstimateValue(22_750_000, UnitSystem.METRIC))

            assertEquals("101", formatEstimateValue(101_250_000, UnitSystem.METRIC))
            assertEquals("1333", formatEstimateValue(1_333_333_376, UnitSystem.METRIC))
        }

    @Test
    fun `a projection rounds in the unit it is read in`() =
        withLocale(Locale.UK) {
            // Rounded after the conversion, not before it: 101.25 kg is 223.22 lb, and an
            // imperial reader must see the whole number their own unit lands on.
            assertEquals("101 kg", formatEstimate(101_250_000, UnitSystem.METRIC))
            assertEquals("223 lb", formatEstimate(101_250_000, UnitSystem.IMPERIAL))
        }

    @Test
    fun `a weight that rounds up to a whole number loses its decimals too`() =
        withLocale(Locale.UK) {
            // 99.998 kg must read as `100`, not as `100.00`: the rounding happens first and the
            // "is it whole" question is asked of the result, not of the input.
            assertEquals("100", formatWeightValue(99_998_000, UnitSystem.METRIC))
        }

    @Test
    fun `a weight never rounds down at the display precision`() =
        withLocale(Locale.UK) {
            // The failure this guards is the one the issue names: a weight that reads low is a
            // plate the user goes looking for and cannot find. Half rounds away from zero.
            assertEquals("2.5", formatWeightValue(2_495_000, UnitSystem.METRIC))
            assertEquals("80", formatWeightValue(79_999_999, UnitSystem.METRIC))
            assertEquals("80", formatWeightValue(80_000_000, UnitSystem.METRIC))
        }

    @Test
    fun `the suffix names the unit and nothing else`() {
        assertEquals("kg", UnitSystem.METRIC.weightSuffix)
        assertEquals("lb", UnitSystem.IMPERIAL.weightSuffix)
    }

    @Test
    fun `a pound is the international pound, not an approximation of one`() {
        // 453.59237 g exactly. A rounded constant drifts by a plate over a heavy squat.
        assertEquals(453_592.37, MILLIGRAMS_PER_POUND, 0.0)
        assertEquals(1_000_000.0, MILLIGRAMS_PER_KILOGRAM, 0.0)
    }

    @Test
    fun `the decimal separator follows the device locale`() =
        withLocale(Locale.FRANCE) {
            assertEquals("2,5", formatWeightValue(2_500_000, UnitSystem.METRIC))
        }

    @Test
    fun `a weight is not grouped, because it is typed back into a field`() =
        withLocale(Locale.UK) {
            // A thousands separator in a text field is a character the user has to clear before
            // the value parses on some keyboards. Volume, which is only ever read, groups.
            assertEquals("1000", formatWeightValue(1_000_000_000, UnitSystem.METRIC))
        }

    // -- Plate increments -----------------------------------------------------------------

    @Test
    fun `the display precision can express the smallest plate in either system`() {
        // This is *the* reason the format carries two decimals rather than one. If the
        // precision is ever lowered, this fails first and says why: an imperial 1.25 lb
        // micro-plate does not survive one decimal.
        listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL).forEach { units ->
            withLocale(Locale.UK) {
                val increment = units.smallestPlateIncrement
                val milligrams = weightToMilligrams(increment, units)

                assertEquals(
                    "$units loses its smallest plate at the display precision",
                    increment,
                    formatWeightValue(milligrams, units).toDouble(),
                    0.0,
                )
            }
        }
    }

    @Test
    fun `plate increments are per-system and are not conversions of each other`() {
        // 5 lb is 2.27 kg, which is not a plate anybody stocks. A rack is stocked in one
        // system or the other, so the increments must not be derived from one another.
        assertEquals(2.5, UnitSystem.METRIC.plateIncrement, 0.0)
        assertEquals(5.0, UnitSystem.IMPERIAL.plateIncrement, 0.0)
        assertEquals(0.5, UnitSystem.METRIC.smallestPlateIncrement, 0.0)
        assertEquals(1.25, UnitSystem.IMPERIAL.smallestPlateIncrement, 0.0)
    }

    @Test
    fun `rounding to a plate increment lands on a weight the rack can make`() =
        withLocale(Locale.UK) {
            // 81.5 kg is not loadable; 82.5 and 80 are, and the nearer of the two wins.
            assertEquals("82.5", snappedTo(81_500_000, UnitSystem.METRIC))
            assertEquals("80", snappedTo(80_400_000, UnitSystem.METRIC))
            // 83.5 kg is 184.1 lb, which an imperial rack makes as 185.
            assertEquals("185", snappedTo(83_500_000, UnitSystem.IMPERIAL))
        }

    /** [milligrams] snapped onto [units]' plate grid, then rendered in [units]. */
    private fun snappedTo(
        milligrams: Long,
        units: UnitSystem,
    ): String = formatWeightValue(roundToPlateIncrement(milligrams, units), units)

    @Test
    fun `rounding to an increment leaves a weight already on the grid alone`() {
        val onGrid = weightToMilligrams(82.5, UnitSystem.METRIC)
        assertEquals(onGrid, roundToPlateIncrement(onGrid, UnitSystem.METRIC))
    }

    // -- Parsing --------------------------------------------------------------------------

    @Test
    fun `a typed weight becomes the milligrams it names`() =
        withLocale(Locale.UK) {
            assertEquals(80_000_000L, parseWeightMilligrams("80", UnitSystem.METRIC))
            assertEquals(2_500_000L, parseWeightMilligrams("2.5", UnitSystem.METRIC))
            // 45 lb, to the milligram.
            assertEquals(20_411_657L, parseWeightMilligrams("45", UnitSystem.IMPERIAL))
        }

    @Test
    fun `a comma is read as a decimal point whatever the keyboard`() =
        withLocale(Locale.UK) {
            // A user on a French keyboard in a UK locale types `2,5` and means 2.5. Refusing it
            // would be the app being right about something nobody asked.
            assertEquals(2_500_000L, parseWeightMilligrams("2,5", UnitSystem.METRIC))
        }

    @Test
    fun `a weight written in the locale's own numerals parses`() =
        withLocale(Locale.FRANCE) {
            // The field is *seeded* with this: `formatWeightValue` renders through the locale,
            // so without the localised pass the dialog would open in its own error state.
            val seeded = formatWeightValue(22_500_000, UnitSystem.METRIC)

            assertEquals("22,5", seeded)
            assertEquals(22_500_000L, parseWeightMilligrams(seeded, UnitSystem.METRIC))
        }

    @Test
    fun `surrounding whitespace is not a typo`() =
        withLocale(Locale.UK) {
            assertEquals(80_000_000L, parseWeightMilligrams("  80  ", UnitSystem.METRIC))
        }

    @Test
    fun `malformed input is refused rather than half-read`() =
        withLocale(Locale.UK) {
            // `NumberFormat` reads `12abc` as 12 and stops. A partial parse is a typo, not a
            // weight — the user meant something the field cannot honour, and silently storing
            // 12 kg is the worst of the three available answers.
            listOf("", "   ", "abc", "12abc", "12.5.5", ".", "-", "1e5", "NaN", "Infinity", "1d")
                .forEach { input ->
                    assertNull("`$input` should not parse as a weight", parseWeightMilligrams(input, UnitSystem.METRIC))
                }
        }

    @Test
    fun `an out-of-range weight parses, and is refused by SetValidator instead`() =
        withLocale(Locale.UK) {
            // The division of labour the acceptance criterion asks for: parsing answers "is this
            // a number", `SetValidator` answers "is it a weight", and only the second can say
            // which bound was missed. A parser that returned null here would reduce a message the
            // user can act on to "not a number" about a number.
            val negative = parseWeightMilligrams("-5", UnitSystem.METRIC)
            val absurd = parseWeightMilligrams("40000", UnitSystem.METRIC)

            assertEquals(-5_000_000L, negative)
            assertEquals(40_000_000_000L, absurd)
            listOf(negative, absurd).forEach { milligrams ->
                val result = SetValidator.validate(strengthSet(weight = milligrams))
                assertFalse("$milligrams mg should be refused by SetValidator", result.isValid)
            }
        }
}
