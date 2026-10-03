package com.titan.core.ui.format

import com.titan.core.domain.model.PaceStyle
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.paceOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * A best pace, read three ways (#225), in a pinned locale — see `withLocale`.
 *
 * Every figure is given as the whole nanoseconds per centimetre the record is ranked on, built from
 * the distance and the time a user would have logged, so each case reads as the set it came from.
 */
@RunWith(RobolectricTestRunner::class)
class PaceFormatTest {

    @Test
    fun `a run reads as a pace per kilometre, or per mile`() =
        withLocale(Locale.UK) {
            // 5 km in 27:14 — 326.8 s a kilometre, shown to the second.
            val fiveK = paceFor(centimetres = 500_000, seconds = 1_634)

            assertEquals("5:27", formatPaceFigure(fiveK, PaceStyle.PACE, UnitSystem.METRIC))
            assertEquals("/km", paceUnit(PaceStyle.PACE, UnitSystem.METRIC))
            // 525.9 s a mile.
            assertEquals("8:46", formatPaceFigure(fiveK, PaceStyle.PACE, UnitSystem.IMPERIAL))
            assertEquals("/mi", paceUnit(PaceStyle.PACE, UnitSystem.IMPERIAL))
        }

    @Test
    fun `a ride reads as a speed, to one decimal even when it is round`() =
        withLocale(Locale.UK) {
            // 40 km in 1:20:00.
            val ride = paceFor(centimetres = 4_000_000, seconds = 4_800)

            assertEquals("30.0", formatPaceFigure(ride, PaceStyle.SPEED, UnitSystem.METRIC))
            assertEquals("km/h", paceUnit(PaceStyle.SPEED, UnitSystem.METRIC))
            // 18.64 mph.
            assertEquals("18.6", formatPaceFigure(ride, PaceStyle.SPEED, UnitSystem.IMPERIAL))
            assertEquals("mph", paceUnit(PaceStyle.SPEED, UnitSystem.IMPERIAL))
        }

    @Test
    fun `a swim reads as a pace per hundred metres, or per hundred yards`() =
        withLocale(Locale.UK) {
            // 400 m in 7:00 — 1:45 every hundred metres.
            val swim = paceFor(centimetres = 40_000, seconds = 420)

            assertEquals("1:45", formatPaceFigure(swim, PaceStyle.SWIM_PACE, UnitSystem.METRIC))
            assertEquals("/100 m", paceUnit(PaceStyle.SWIM_PACE, UnitSystem.METRIC))
            // A hundred yards is 91.44 m: 96.0 s.
            assertEquals("1:36", formatPaceFigure(swim, PaceStyle.SWIM_PACE, UnitSystem.IMPERIAL))
            assertEquals("/100 yd", paceUnit(PaceStyle.SWIM_PACE, UnitSystem.IMPERIAL))
        }

    @Test
    fun `a pace improvement is the difference of the two paces as shown`() =
        withLocale(Locale.UK) {
            // 5:27 /km over 5 km, then 5:10 /km over 10 km: seventeen seconds a kilometre, though
            // the second run took 24:26 longer.
            val fiveK = paceFor(centimetres = 500_000, seconds = 1_634)
            val tenK = paceFor(centimetres = 1_000_000, seconds = 3_100)

            assertEquals("0:17", formatPaceChange(fiveK, tenK, PaceStyle.PACE, UnitSystem.METRIC))
        }

    @Test
    fun `a speed improvement is the difference of the two speeds as shown`() =
        withLocale(Locale.UK) {
            // 28.5 km/h, then 30.0 km/h.
            val slower = paceFor(centimetres = 2_850_000, seconds = 3_600)
            val faster = paceFor(centimetres = 4_000_000, seconds = 4_800)

            assertEquals("1.5", formatPaceChange(slower, faster, PaceStyle.SPEED, UnitSystem.METRIC))
        }

    @Test
    fun `a speed follows the locale's decimal separator`() =
        withLocale(Locale.FRANCE) {
            val ride = paceFor(centimetres = 4_000_000, seconds = 4_800)

            assertEquals("30,0", formatPaceFigure(ride, PaceStyle.SPEED, UnitSystem.METRIC))
        }

    /** The pace the record would be ranked on — the domain's own, so the two cannot drift. */
    private fun paceFor(
        centimetres: Long,
        seconds: Long,
    ): Long = checkNotNull(paceOf(durationSeconds = seconds, distanceCentimetres = centimetres))
}
