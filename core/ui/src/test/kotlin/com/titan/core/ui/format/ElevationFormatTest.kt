package com.titan.core.ui.format

import com.titan.core.domain.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/** The climb formatter, in a pinned locale for [LengthFormatTest]'s reason. */
@RunWith(RobolectricTestRunner::class)
class ElevationFormatTest {

    @Test
    fun `a climb reads in metres or feet, whatever its size`() =
        withLocale(Locale.UK) {
            assertEquals("120 m", formatElevation(12_000L, UnitSystem.METRIC))
            assertEquals("1200 m", formatElevation(120_000L, UnitSystem.METRIC))
            assertEquals("394 ft", formatElevation(12_000L, UnitSystem.IMPERIAL))
        }

    @Test
    fun `a field is seeded with a whole number and no unit`() =
        withLocale(Locale.UK) {
            assertEquals("120", formatElevationValue(12_000L, UnitSystem.METRIC))
            assertEquals("394", formatElevationValue(12_000L, UnitSystem.IMPERIAL))
        }

    @Test
    fun `a typed climb goes back into centimetres in the system's unit`() =
        withLocale(Locale.UK) {
            assertEquals(12_000L, parseElevationCentimetres("120", UnitSystem.METRIC))
            assertEquals(30_480L, parseElevationCentimetres("1000", UnitSystem.IMPERIAL))
            assertNull(parseElevationCentimetres("lots", UnitSystem.METRIC))
        }

    @Test
    fun `a whole-foot climb survives being shown, typed and shown again`() =
        withLocale(Locale.UK) {
            val stored = parseElevationCentimetres("394", UnitSystem.IMPERIAL)!!

            assertEquals("394", formatElevationValue(stored, UnitSystem.IMPERIAL))
        }
}
