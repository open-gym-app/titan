package com.titan.core.ui.format

import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The time and distance a timed set is typed in (#M7-17): what the parser reads, what it refuses,
 * and the unit a distance field is typed in.
 *
 * Robolectric for the ICU formatting the round trip goes through — see [withLocale].
 */
@RunWith(RobolectricTestRunner::class)
class ClockEntryTest {

    @Test
    fun `minutes and seconds read as seconds`() {
        assertEquals(90L, parseClockSeconds("1:30"))
        assertEquals(1_634L, parseClockSeconds("27:14"))
        assertEquals(45L, parseClockSeconds("0:45"))
        // The leading field is unbounded: ninety minutes is not something to convert first.
        assertEquals(5_400L, parseClockSeconds("90:00"))
    }

    @Test
    fun `hours minutes and seconds read as seconds`() {
        assertEquals(3_930L, parseClockSeconds("1:05:30"))
        assertEquals(36_000L, parseClockSeconds("10:00:00"))
    }

    @Test
    fun `the decimal pad's full stop and comma stand in for the colon`() {
        assertEquals(90L, parseClockSeconds("1.30"))
        assertEquals(90L, parseClockSeconds("1,30"))
        assertEquals(3_930L, parseClockSeconds("1.05.30"))
        assertEquals(90L, parseClockSeconds("  1:30 "))
    }

    @Test
    fun `a bare number counts what the exercise says it counts`() {
        assertEquals(45L, parseClockSeconds("45"))
        assertEquals(45L, parseClockSeconds("45", BareClockNumber.SECONDS))
        assertEquals(1_800L, parseClockSeconds("30", BareClockNumber.MINUTES))
        assertEquals(BareClockNumber.MINUTES, ExerciseType.ENDURANCE.bareClockNumber)
        assertEquals(BareClockNumber.SECONDS, ExerciseType.ISOMETRIC.bareClockNumber)
        assertEquals(BareClockNumber.SECONDS, (null as ExerciseType?).bareClockNumber)
    }

    @Test
    fun `negative - an ambiguous or malformed time is refused rather than guessed at`() {
        // A minute and a half to the lifter, a minute and five seconds to a lax parser.
        assertNull(parseClockSeconds("1.5"))
        assertNull(parseClockSeconds("1:75"))
        assertNull(parseClockSeconds("1:60:00"))
        assertNull(parseClockSeconds(":30"))
        assertNull(parseClockSeconds("1:30:"))
        assertNull(parseClockSeconds("1:02:03:04"))
        assertNull(parseClockSeconds("abc"))
        assertNull(parseClockSeconds("-1:30"))
        assertNull(parseClockSeconds(""))
        assertNull(parseClockSeconds("1234567890"))
    }

    @Test
    fun `zero parses, and is left to SetValidator to refuse by name`() {
        assertEquals(0L, parseClockSeconds("0:00"))
    }

    @Test
    fun `a time put back into a field parses to the same seconds`() =
        FORMATTING_LOCALES.forEach { locale ->
            withLocale(locale) {
                listOf(1L, 45L, 90L, 1_634L, 3_930L, 86_399L).forEach { seconds ->
                    assertEquals("$locale $seconds", seconds, parseClockSeconds(formatClockValue(seconds)))
                }
            }
        }

    @Test
    fun `every exercise types its distance in the system's long unit`() {
        val run = Exercise(name = "Running, Outdoor", type = ExerciseType.ENDURANCE)
        val swim = Exercise(name = "Swimming, Freestyle", type = ExerciseType.ENDURANCE)

        assertEquals(DistanceUnit.KILOMETRE, UnitSystem.METRIC.distanceEntryUnit)
        assertEquals(DistanceUnit.MILE, UnitSystem.IMPERIAL.distanceEntryUnit)
        listOf(run, swim, null).forEach { exercise ->
            assertEquals(DistanceUnit.KILOMETRE, EntryUnits.of(exercise, UnitSystem.METRIC).distanceUnit)
            assertEquals(DistanceUnit.MILE, EntryUnits.of(exercise, UnitSystem.IMPERIAL).distanceUnit)
        }
    }

    @Test
    fun `5 km typed into a run's field is half a million centimetres`() =
        withLocale(Locale.UK) {
            assertEquals(500_000L, parseDistanceCentimetres("5", DistanceUnit.KILOMETRE))
        }

    @Test
    fun `a pool length typed in kilometres or miles keeps its metre or yard`() =
        withLocale(Locale.UK) {
            // A readout's two decimals would store 25 m as 30 m and 50 yd as 0.03 mi, 53 yd.
            assertEquals(2_500L, parseDistanceCentimetres("0.025", DistanceUnit.KILOMETRE))
            assertEquals("0.025", formatDistanceEntry(2_500L, DistanceUnit.KILOMETRE))
            assertEquals(4_828L, parseDistanceCentimetres("0.03", DistanceUnit.MILE))
            assertEquals(4_506L, parseDistanceCentimetres("0.028", DistanceUnit.MILE))
            listOf(2_500L, 40_000L, 520_000L, 4_219_500L).forEach { centimetres ->
                val typed = formatDistanceEntry(centimetres, DistanceUnit.KILOMETRE)
                assertEquals(typed, centimetres, parseDistanceCentimetres(typed, DistanceUnit.KILOMETRE))
            }
        }
}
