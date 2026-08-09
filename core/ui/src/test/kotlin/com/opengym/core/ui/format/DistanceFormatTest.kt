package com.opengym.core.ui.format

import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.validation.SetValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** The distance formatter, in a pinned locale. See `withLocale`. */
class DistanceFormatTest {

    @Test
    fun `a run is kilometres, or miles`() = withLocale(Locale.UK) {
        // 5.2 km, the wireframes' cardio row.
        assertEquals("5.2 km", formatDistance(520_000, UnitSystem.METRIC))
        assertEquals("3.23 mi", formatDistance(520_000, UnitSystem.IMPERIAL))
    }

    @Test
    fun `a short distance reads in the short unit rather than as a fraction of a long one`() =
        withLocale(Locale.UK) {
            // `0.1 km` is a worse answer than `100 m` for every interval a gym actually logs,
            // and the app logs 25 m pool lengths and 400 m repeats.
            assertEquals("100 m", formatDistance(10_000, UnitSystem.METRIC))
            assertEquals("400 m", formatDistance(40_000, UnitSystem.METRIC))
            assertEquals("109 yd", formatDistance(10_000, UnitSystem.IMPERIAL))
        }

    @Test
    fun `the unit switches at a kilometre, and at half a mile`() = withLocale(Locale.UK) {
        assertEquals(DistanceUnit.METRE, UnitSystem.METRIC.distanceUnitFor(99_999))
        assertEquals(DistanceUnit.KILOMETRE, UnitSystem.METRIC.distanceUnitFor(100_000))
        assertEquals(DistanceUnit.YARD, UnitSystem.IMPERIAL.distanceUnitFor(80_467 - 1))
        assertEquals(DistanceUnit.MILE, UnitSystem.IMPERIAL.distanceUnitFor(80_467 + 1))
    }

    @Test
    fun `the short units carry no decimals`() = withLocale(Locale.UK) {
        // A metre is already finer than any treadmill or GPS reports, so `437.2 yd` claims a
        // precision nobody has.
        assertEquals("437 yd", formatDistance(40_000, UnitSystem.IMPERIAL))
        assertEquals("25 m", formatDistance(2_500, UnitSystem.METRIC))
    }

    @Test
    fun `a marathon is the distance it is meant to be`() = withLocale(Locale.UK) {
        // 42.195 km, to the metre. Two decimals is where it lands.
        assertEquals("42.2 km", formatDistance(4_219_500, UnitSystem.METRIC))
        assertEquals("26.22 mi", formatDistance(4_219_500, UnitSystem.IMPERIAL))
    }

    @Test
    fun `every conversion factor is exact by definition`() {
        assertEquals(91.44, DistanceUnit.YARD.centimetres, 0.0)
        assertEquals(160_934.4, DistanceUnit.MILE.centimetres, 0.0)
        assertEquals(DistanceUnit.MILE.centimetres, DistanceUnit.YARD.centimetres * 1_760, 1e-9)
        assertEquals(100_000.0, DistanceUnit.KILOMETRE.centimetres, 0.0)
    }

    @Test
    fun `the suffix and the value can be drawn separately`() = withLocale(Locale.UK) {
        // A stat tile sets the figure in a numeric role and the unit in a smaller one, so it
        // needs the two apart rather than joined.
        assertEquals("5.2", formatDistanceValue(520_000, UnitSystem.METRIC))
        assertEquals("km", distanceSuffix(520_000, UnitSystem.METRIC))
        assertEquals("m", distanceSuffix(40_000, UnitSystem.METRIC))
    }

    @Test
    fun `the decimal separator follows the device locale`() = withLocale(Locale.FRANCE) {
        assertEquals("5,2 km", formatDistance(520_000, UnitSystem.METRIC))
    }

    @Test
    fun `a typed distance becomes the centimetres it names`() = withLocale(Locale.UK) {
        assertEquals(520_000L, parseDistanceCentimetres("5.2", DistanceUnit.KILOMETRE))
        assertEquals(40_000L, parseDistanceCentimetres("400", DistanceUnit.METRE))
        assertEquals(160_934L, parseDistanceCentimetres("1", DistanceUnit.MILE))
    }

    @Test
    fun `malformed input is refused rather than half-read`() = withLocale(Locale.UK) {
        listOf("", "abc", "5 km", "5..2", "-").forEach { input ->
            assertNull(
                "`$input` should not parse as a distance",
                parseDistanceCentimetres(input, DistanceUnit.KILOMETRE),
            )
        }
    }

    @Test
    fun `an out-of-range distance parses, and is refused by SetValidator instead`() = withLocale(Locale.UK) {
        // The same division of labour as the weight parser: syntax here, bounds there, so the
        // message a user sees names the bound rather than saying "not a number" about one.
        val absurd = parseDistanceCentimetres("100000", DistanceUnit.KILOMETRE)

        assertEquals(10_000_000_000L, absurd)
        assertFalse(SetValidator.validate(strengthSet(distance = absurd)).isValid)
    }
}
