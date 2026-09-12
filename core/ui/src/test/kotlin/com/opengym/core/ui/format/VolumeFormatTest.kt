package com.opengym.core.ui.format

import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.stats.VolumeCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/** The volume formatter, in a pinned locale. See `withLocale`. */
@RunWith(RobolectricTestRunner::class)
class VolumeFormatTest {

    @Test
    fun `a session total is grouped and whole`() =
        withLocale(Locale.UK) {
            // The wireframes' summary tile: `4,820 kg`.
            assertEquals("4,820 kg", formatVolume(4_820_000_000, UnitSystem.METRIC))
            assertEquals("10,626 lb", formatVolume(4_820_000_000, UnitSystem.IMPERIAL))
        }

    @Test
    fun `the tenths of a kilogram are dropped`() =
        withLocale(Locale.UK) {
            // 4,820.4 kg is twelve sets of squats and a rounding artefact. The tenth that matters
            // on one set stops meaning anything once a hundred of them are added up.
            assertEquals("4,820", formatVolumeValue(4_820_400_000, UnitSystem.METRIC))
            assertEquals("4,821", formatVolumeValue(4_820_600_000, UnitSystem.METRIC))
        }

    @Test
    fun `a total under a thousand carries no separator`() =
        withLocale(Locale.UK) {
            assertEquals("0 kg", formatVolume(0, UnitSystem.METRIC))
            assertEquals("960 kg", formatVolume(960_000_000, UnitSystem.METRIC))
        }

    @Test
    fun `the thousands separator follows the device locale`() {
        // A hand-rolled comma every three digits would read as 1.234 in half of Europe. This
        // asserts the separator is the locale's without asserting *which* character it is —
        // France uses a narrow no-break space, and which one the JDK picks has moved between
        // versions.
        withLocale(Locale.GERMANY) {
            assertEquals("4.820", formatVolumeValue(4_820_000_000, UnitSystem.METRIC))
        }
        withLocale(Locale.FRANCE) {
            val grouped = formatVolumeValue(4_820_000_000, UnitSystem.METRIC)

            assertEquals("4820", grouped.filter { it.isDigit() })
            assertEquals(1, grouped.count { !it.isDigit() })
        }
    }

    @Test
    fun `an all-time total stays readable`() =
        withLocale(Locale.UK) {
            // The progression screen's `CUMULATIVE VOLUME`, which runs into the millions.
            assertEquals("1,240,000 kg", formatVolume(1_240_000_000_000, UnitSystem.METRIC))
        }

    @Test
    fun `the figure a session summary shows is the one the domain computed`() =
        withLocale(Locale.UK) {
            // The formatter's input is `VolumeCalculator`'s output and nothing else — milligram
            // reps, summed in the domain, converted once here at the end.
            val sets = List(4) { strengthSet(weight = 100_000_000) }

            assertEquals(3_200_000_000L, VolumeCalculator.totalVolume(sets))
            assertEquals("3,200 kg", formatVolume(VolumeCalculator.totalVolume(sets), UnitSystem.METRIC))
        }
}
