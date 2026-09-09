package com.opengym.core.ui.format

import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.validation.BodyMeasurementValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The circumference formatter, in a pinned locale.
 *
 * [withLocale] for the reason `WeightFormatTest` pins one: the formatter is locale-sensitive
 * by design and a test asserting on `40.2` would otherwise fail under a CI machine set to
 * `fr-FR`, for the one reason that is not a bug.
 */
@RunWith(RobolectricTestRunner::class)
class LengthFormatTest {

    @Test
    fun `a metric tape reads in centimetres and an imperial one in inches`() =
        withLocale(Locale.UK) {
            assertEquals("102 cm", formatLength(CHEST_MM, UnitSystem.METRIC))
            assertEquals("40.2 in", formatLength(CHEST_MM, UnitSystem.IMPERIAL))
        }

    @Test
    fun `a whole measurement loses its trailing zero`() =
        withLocale(Locale.UK) {
            assertEquals("102", formatLengthValue(1_020, UnitSystem.METRIC))
            assertEquals("0", formatLengthValue(0, UnitSystem.METRIC))
        }

    @Test
    fun `one decimal, and no more`() =
        withLocale(Locale.UK) {
            // A millimetre is a tenth of a centimetre, so metric is exact at this precision.
            assertEquals("82.3", formatLengthValue(823, UnitSystem.METRIC))
            // Two decimals would claim a precision a tape pulled by hand does not have.
            assertEquals("32.4", formatLengthValue(823, UnitSystem.IMPERIAL))
        }

    @Test
    fun `it is not the distance formatter`() =
        withLocale(Locale.UK) {
            // A chest is 1.02 m and 1.12 yd, and both are the wrong answer on a body screen:
            // `DistanceFormat` picks its unit from the magnitude and answers imperial in yards.
            assertEquals("102 cm", formatLength(CHEST_MM, UnitSystem.METRIC))
            assertEquals("40.2 in", formatLength(CHEST_MM, UnitSystem.IMPERIAL))
        }

    @Test
    fun `a typed measurement comes back as millimetres`() =
        withLocale(Locale.UK) {
            assertEquals(1_020, parseLengthMillimetres("102", UnitSystem.METRIC))
            assertEquals(1_021, parseLengthMillimetres("40.2", UnitSystem.IMPERIAL))
        }

    @Test
    fun `anything that is not a number is null, whatever its magnitude`() =
        withLocale(Locale.UK) {
            assertNull(parseLengthMillimetres("", UnitSystem.METRIC))
            assertNull(parseLengthMillimetres("wide", UnitSystem.METRIC))
            // Out of range is not this function's judgement to make — the bound belongs to
            // `BodyMeasurementValidator`, which can say why.
            assertEquals(
                BodyMeasurementValidator.CIRCUMFERENCE_MAX_MM * 2,
                parseLengthMillimetres("1000", UnitSystem.METRIC),
            )
        }

    @Test
    fun `everything the formatter renders parses back to the same string`() =
        withLocale(Locale.UK) {
            // The round trip a form leans on: opening the sheet on a stored measurement and
            // saving it untouched must not move the value. Every millimetre across the range a
            // body occupies, in both systems.
            UnitSystem.entries.forEach { unitSystem ->
                (MIN_BODY_MM..MAX_BODY_MM).forEach { millimetres ->
                    val rendered = formatLengthValue(millimetres, unitSystem)
                    val parsed = requireNotNull(parseLengthMillimetres(rendered, unitSystem))
                    assertEquals(rendered, formatLengthValue(parsed, unitSystem))
                }
            }
        }

    @Test
    fun `a typed measurement renders back to what was typed`() =
        withLocale(Locale.UK) {
            // The other direction, over the grid a tape is actually read to: whole millimetres
            // metric, tenths of an inch imperial.
            (MIN_BODY_MM..MAX_BODY_MM).forEach { millimetres ->
                val typed = formatLengthValue(millimetres, UnitSystem.METRIC)
                assertEquals(millimetres, parseLengthMillimetres(typed, UnitSystem.METRIC))
            }
        }

    @Test
    fun `the decimal separator follows the locale, in both directions`() {
        withLocale(Locale.FRANCE) {
            assertEquals("40,2", formatLengthValue(1_021, UnitSystem.IMPERIAL))
            assertEquals(1_021, parseLengthMillimetres("40,2", UnitSystem.IMPERIAL))
            // And a value typed on an ASCII keyboard still means what it says — the generous
            // half of `parseNumber`, which every formatter in this package inherits.
            assertEquals(1_021, parseLengthMillimetres("40.2", UnitSystem.IMPERIAL))
        }
    }

    private companion object {
        /** A 102 cm chest, in the millimetres the schema stores. */
        const val CHEST_MM = 1_020

        /** A wrist. */
        const val MIN_BODY_MM = 100

        /** A very large thigh, and past any chest. */
        const val MAX_BODY_MM = 1_500
    }
}
