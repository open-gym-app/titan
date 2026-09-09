package com.opengym.core.ui.format

import com.opengym.core.domain.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every formatter, in all three locales the app ships in.
 *
 * The rest of this package's tests pin one locale and assert a value — that a weight rounds
 * up, that an estimate floors at five minutes — and are about arithmetic that does not change
 * with the language. This one is about the half that does, and it exists because a suite that
 * only ran in English passed every version of these formatters, including the ones that wrote
 * `"$value kg"` and put the weekday in front of the date in every language on earth.
 *
 * **The expectations are written out per locale rather than derived.** Deriving them would
 * mean calling the same CLDR lookup the formatter calls, which asserts nothing: the test would
 * pass on a formatter that skipped the lookup as long as it skipped it the same way twice.
 * Typed out, a `de` missing from `abril de 2026` is a failure with the answer in it.
 *
 * That does make these sensitive to a CLDR update, which is the trade being made knowingly:
 * the numbers are pinned by `robolectric.properties` (see `WriteRobolectricConfig`), so they
 * move when the SDK pin moves and not on whoever's laptop is running the build. A CLDR change
 * that reaches these is a change to what users read, and a failing assertion is the right
 * place to find out.
 */
@RunWith(RobolectricTestRunner::class)
class LocalizedFormatTest {

    /** 2026-04-28, a Tuesday — the same day `DateTimeFormatTest` pins. */
    private val date = LocalDate.of(2026, 4, 28)

    private val expectations = listOf(
        Expected(
            locale = Locale.US,
            weight = "20 kg",
            convertedWeight = "44.09 lb",
            bareValue = "22.5",
            tapeUnit = "in",
            circumference = "40.2 in",
            distance = "5.2 km",
            volume = "4,820 kg",
            count = "2,140",
            sessionLength = "52m",
            longSessionLength = "1h 3m",
            sessionDate = "Tue, Apr 28, 2026",
            monthAndYear = "April 2026",
            dayAndMonth = "Apr 28",
        ),
        Expected(
            locale = Locale.FRANCE,
            // `\u202F` is a narrow no-break space, and it is what French puts between a figure
            // and its unit and between its thousands groups. Written as an escape rather than
            // pasted: it is indistinguishable from a plain space in a diff, and only one of the
            // two is right. It is also the character a hand-rolled comma-every-three-digits
            // would never have produced.
            weight = "20\u202Fkg",
            convertedWeight = "44,09\u202Flb",
            bareValue = "22,5",
            // The inch is the unit that makes the case: `in` is not a symbol, it is English.
            tapeUnit = "po",
            circumference = "40,2\u202Fpo",
            distance = "5,2\u202Fkm",
            volume = "4\u202F820\u202Fkg",
            count = "2\u202F140",
            sessionLength = "52min",
            longSessionLength = "1h 3min",
            sessionDate = "mar. 28 avr. 2026",
            monthAndYear = "avril 2026",
            dayAndMonth = "28 avr.",
        ),
        Expected(
            locale = Locale.forLanguageTag("pt-BR"),
            weight = "20 kg",
            convertedWeight = "44,09 lb",
            bareValue = "22,5",
            tapeUnit = "pol.",
            circumference = "40,2 pol.",
            distance = "5,2 km",
            // A full stop groups thousands here and a comma opens the fraction — the exact
            // inversion of English, and a thousandfold error in either direction.
            volume = "4.820 kg",
            count = "2.140",
            sessionLength = "52 min",
            longSessionLength = "1 h 3 min",
            // Two `de`s and a comma: the join between a day and its month is a word here.
            sessionDate = "ter., 28 de abr. de 2026",
            monthAndYear = "abril de 2026",
            dayAndMonth = "28 de abr.",
        ),
    )

    @Test
    fun `a weight carries the locale's own decimal separator and unit`() =
        forEachLocale { expected ->
            assertEquals(expected.weight, formatWeight(20_000_000, UnitSystem.METRIC))
            assertEquals(expected.convertedWeight, formatWeight(20_000_000, UnitSystem.IMPERIAL))
            assertEquals(expected.bareValue, formatWeightValue(22_500_000, UnitSystem.METRIC))
        }

    @Test
    fun `a tape unit is translated, because an inch is not a symbol`() =
        forEachLocale { expected ->
            // `kg` survives translation because the SI symbol happens to be universal, which is
            // a fact about kilograms rather than a rule about units. The inch is the proof.
            assertEquals("kg", UnitSystem.METRIC.weightSuffix)
            assertEquals(expected.tapeUnit, UnitSystem.IMPERIAL.lengthSuffix)
            assertEquals(expected.circumference, formatLength(1_020, UnitSystem.IMPERIAL))
        }

    @Test
    fun `a distance keeps its unit and moves its separator`() =
        forEachLocale { expected ->
            assertEquals(expected.distance, formatDistance(520_000, UnitSystem.METRIC))
            // The short unit is a bare `m` in all three; the long one is where the decimal is.
            assertEquals("m", DistanceUnit.METRE.suffix)
        }

    @Test
    fun `a grouped figure groups the way the locale does`() =
        forEachLocale { expected ->
            assertEquals(expected.volume, formatVolume(4_820_000_000, UnitSystem.METRIC))
            assertEquals(expected.count, formatCount(2_140))
        }

    @Test
    fun `a duration's unit letters are words in some languages`() =
        forEachLocale { expected ->
            assertEquals(expected.sessionLength, (52.minutes + 14.seconds).formatAsLength())
            assertEquals(expected.longSessionLength, (1.hours + 3.minutes).formatAsLength())
        }

    @Test
    fun `a date is assembled by the locale rather than by this package`() =
        forEachLocale { expected ->
            // The three shapes that used to be built by hand. Every difference between the rows
            // below is a join that a `"$weekday $rest"` could not have produced.
            assertEquals(expected.sessionDate, formatSessionDate(date))
            assertEquals(expected.monthAndYear, formatMonthAndYear(YearMonth.of(2026, 4)))
            assertEquals(expected.dayAndMonth, formatDayAndMonth(date))
        }

    @Test
    fun `a weight typed back in the locale it was written in round-trips`() =
        forEachLocale { _ ->
            // The pair that has to hold in every locale, not only in the one the field was
            // designed in: what the app seeds a field with is what the app must read back.
            val seeded = formatWeightValue(22_500_000, UnitSystem.METRIC)

            assertEquals(22_500_000L, parseWeightMilligrams(seeded, UnitSystem.METRIC))
        }

    /** Runs [block] once per locale, with that locale's expectations and as the default. */
    private fun forEachLocale(block: (Expected) -> Unit) {
        assertEquals("every shipping locale needs a row", FORMATTING_LOCALES, expectations.map { it.locale })
        expectations.forEach { expected -> withLocale(expected.locale) { block(expected) } }
    }

    /** What one locale writes each quantity as. One row per locale, and no row derived. */
    private data class Expected(
        val locale: Locale,
        val weight: String,
        val convertedWeight: String,
        val bareValue: String,
        val tapeUnit: String,
        val circumference: String,
        val distance: String,
        val volume: String,
        val count: String,
        val sessionLength: String,
        val longSessionLength: String,
        val sessionDate: String,
        val monthAndYear: String,
        val dayAndMonth: String,
    )
}
