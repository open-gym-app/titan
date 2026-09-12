package com.opengym.core.ui.format

import com.opengym.core.domain.validation.BodyMeasurementValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The body-fat formatter, in a pinned locale — see `WeightFormatTest` for why every assertion
 * on a literal string pins one.
 */
@RunWith(RobolectricTestRunner::class)
class PercentFormatTest {

    @Test
    fun `tenths of a percent read as a percentage`() =
        withLocale(Locale.UK) {
            assertEquals("15.5%", formatPercentTenths(155))
            assertEquals("14.2%", formatPercentTenths(142))
        }

    @Test
    fun `a whole percentage loses its trailing zero`() =
        withLocale(Locale.UK) {
            assertEquals("14%", formatPercentTenths(140))
            assertEquals("14", formatPercentTenthsValue(140))
        }

    @Test
    fun `the value form carries no symbol, for a field that draws its own`() =
        withLocale(Locale.UK) {
            assertEquals("15.5", formatPercentTenthsValue(155))
            assertEquals("%", percentSuffix)
        }

    @Test
    fun `both bounds render as the figure a user would have typed`() =
        withLocale(Locale.UK) {
            // Integer division alone would render the floor as `0%` and tell somebody who
            // entered 0.1% that the bound they broke includes their value.
            assertEquals("0.1%", formatPercentTenths(BodyMeasurementValidator.BODY_FAT_MIN))
            assertEquals("100%", formatPercentTenths(BodyMeasurementValidator.BODY_FAT_MAX))
        }

    @Test
    fun `a typed percentage comes back in tenths`() =
        withLocale(Locale.UK) {
            assertEquals(155, parsePercentTenths("15.5"))
            assertEquals(140, parsePercentTenths("14"))
            // Rounded to the unit it is stored in rather than truncated: a figure entered to
            // two decimals is a scale claiming a precision the column does not hold.
            assertEquals(143, parsePercentTenths("14.25"))
        }

    @Test
    fun `anything that is not a number is null, whatever its magnitude`() =
        withLocale(Locale.UK) {
            assertNull(parsePercentTenths(""))
            assertNull(parsePercentTenths("lean"))
            // Out of range is `BodyMeasurementValidator`'s judgement, not the parser's.
            assertEquals(2_000, parsePercentTenths("200"))
        }

    @Test
    fun `everything the value form renders parses back to the same tenths`() =
        withLocale(Locale.UK) {
            // The round trip the form leans on, over every body-fat figure the schema can hold.
            (BodyMeasurementValidator.BODY_FAT_MIN..BodyMeasurementValidator.BODY_FAT_MAX).forEach { tenths ->
                assertEquals(tenths, parsePercentTenths(formatPercentTenthsValue(tenths)))
            }
        }

    @Test
    fun `the symbol follows the locale rather than being written as a glyph`() {
        withLocale(Locale.FRANCE) {
            // French puts a no-break space before the sign; Turkish puts the sign in front of
            // the number. Neither is something a format string gets right.
            assertEquals("15,5", formatPercentTenthsValue(155))
            assertEquals(155, parsePercentTenths("15,5"))
        }
    }
}
