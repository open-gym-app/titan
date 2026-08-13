package com.opengym.core.ui.format

import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.milligramsPerUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.absoluteValue

/**
 * The property the whole package exists to hold: **a value the user entered reads back as
 * the value the user entered**, in either unit system and in any locale.
 *
 * Asserted over the grid of weights a gym can actually load rather than over random
 * doubles, because a round trip that held for 37.913 kg and failed for 82.5 kg would pass a
 * random-value test and fail every user. See `loadableWeights`.
 *
 * ### Which round trip
 *
 * `display -> canonical -> display` is the one that is exactly true and the one that
 * matters: it is what a text field seeded with a stored value and saved untouched does, and
 * it is what "values entered in either system read back unchanged" means.
 *
 * `canonical -> display -> canonical` is exact **only for a canonical value that came from
 * that display unit**, which is asserted below as the same property read the other way
 * round. It cannot hold universally, and a test that claimed it did would be wrong rather
 * than strict: a 20 kg bar is 44.09 lb at the display precision, and no imperial rendering
 * of it can recover the milligram it was stored as. That is a property of pounds, not a
 * defect — which is why display, not canonical, is the fixed point.
 *
 * The locales are the three that break a naive implementation in different ways: [Locale.UK]
 * as the baseline, [Locale.FRANCE] for a decimal comma and a non-breaking group separator,
 * and Persian for digits that are not ASCII at all.
 */
class UnitRoundTripTest {

    private val locales = listOf(Locale.UK, Locale.FRANCE, Locale.forLanguageTag("fa-IR"))

    @Test
    fun `a loadable weight survives being displayed and typed back in`() {
        forEachLocaleAndSystem { locale, units ->
            loadableWeights(units).forEach { weight ->
                val milligrams = weightToMilligrams(weight, units)
                val displayed = formatWeightValue(milligrams, units)
                val reparsed = parseWeightMilligrams(displayed, units)

                assertEquals(
                    "$weight ${units.weightSuffix} did not survive the round trip in $locale",
                    displayed,
                    reparsed?.let { formatWeightValue(it, units) },
                )
            }
        }
    }

    @Test
    fun `a loadable weight reaches storage as exactly the same milligrams it left`() {
        forEachLocaleAndSystem { locale, units ->
            loadableWeights(units).forEach { weight ->
                // The canonical direction, over the values it is defined for: milligram-exact,
                // not merely close. This is what makes opening and saving a set without
                // touching it a no-op rather than a gram of drift per edit.
                val milligrams = weightToMilligrams(weight, units)
                val stored = parseWeightMilligrams(formatWeightValue(milligrams, units), units)

                assertEquals(
                    "$weight ${units.weightSuffix} drifted in $locale",
                    milligrams,
                    stored,
                )
            }
        }
    }

    @Test
    fun `a trip through the other unit system never costs a plate`() {
        // The one cross-system claim that is true. A user who switches to the other system,
        // re-saves the value the field shows them and switches back has been through two
        // display roundings, so the milligrams are not identical — but the error is bounded
        // by the display step, which is three orders of magnitude under the smallest plate
        // anybody racks. In other words: they cannot lose a micro-plate this way.
        forEachLocaleAndSystem { locale, units ->
            val other = if (units == UnitSystem.METRIC) UnitSystem.IMPERIAL else UnitSystem.METRIC
            loadableWeights(units).forEach { weight ->
                val milligrams = weightToMilligrams(weight, units)
                val viaOther = parseWeightMilligrams(formatWeightValue(milligrams, other), other)!!
                val drift = (viaOther - milligrams).absoluteValue / units.milligramsPerUnit

                assertTrue(
                    "$weight ${units.weightSuffix} drifted $drift via $other in $locale",
                    drift < units.smallestPlateIncrement / TOLERANCE_DIVISOR,
                )
            }
        }
    }

    @Test
    fun `a distance survives being displayed and typed back in`() {
        // Every unit, not only the one a given distance would be shown in: an input field
        // names its unit, and a 25 m pool length typed into a `km` field must still be the
        // distance it names.
        forEachLocale { locale ->
            DistanceUnit.entries.forEach { unit ->
                distancesIn(unit).forEach { distance ->
                    val centimetres = (distance * unit.centimetres).toLong()
                    val displayed = formatDistanceValue(centimetres, unit)
                    val reparsed = parseDistanceCentimetres(displayed, unit)

                    assertEquals(
                        "$distance ${unit.suffix} did not survive the round trip in $locale",
                        displayed,
                        reparsed?.let { formatDistanceValue(it, unit) },
                    )
                }
            }
        }
    }

    @Test
    fun `a distance reaches storage as the centimetres its own field shows back`() {
        // The canonical direction for distances, and the one the short units can fail: a metre
        // field shows no decimals, so a value that keeps them lands on a different number of
        // centimetres every time the set is opened and saved untouched. Saving twice has to be
        // saving once.
        forEachLocale { locale ->
            DistanceUnit.entries.forEach { unit ->
                distancesIn(unit).forEach { distance ->
                    val centimetres = (distance * unit.centimetres).toLong()
                    val stored = parseDistanceCentimetres(formatDistanceValue(centimetres, unit), unit)!!

                    assertEquals(
                        "$distance ${unit.suffix} drifted on the second save in $locale",
                        stored,
                        parseDistanceCentimetres(formatDistanceValue(stored, unit), unit),
                    )
                }
            }
        }
    }

    @Test
    fun `a volume total shows the whole units it holds, in every locale`() {
        // Volume has no parser, because it has no input field — it is a computed total, and
        // the only round trip that exists for it is that the digits on screen are the whole
        // units of the value behind them, whatever the locale does to the separators. That is
        // the assertion that would catch a grouped `4,820` having become 4.82 somewhere.
        forEachLocaleAndSystem { locale, units ->
            listOf(0L, 1L, 999L, 4_820L, 10_626L, 1_234_567L).forEach { displayUnits ->
                val milligrams = weightToMilligrams(displayUnits.toDouble(), units)
                val digits = formatVolumeValue(milligrams, units).filter { it.isDigit() }

                assertEquals(
                    "a volume of $displayUnits ${units.weightSuffix} lost its magnitude in $locale",
                    displayUnits.toString().length,
                    digits.length,
                )
            }
        }
    }

    @Test
    fun `a grouped total is not read back as a fraction of itself`() {
        // The ambiguity the parser has to settle: `4,820` is four thousand to a British
        // reader and four-point-eight to a French one. Weights are rendered ungrouped so a
        // field never has to face it, but a user typing a leg press by hand still can.
        withLocale(Locale.UK) {
            assertEquals(4_820_000_000L, parseWeightMilligrams("4,820", UnitSystem.METRIC))
            assertEquals(2_500_000L, parseWeightMilligrams("2,5", UnitSystem.METRIC))
            // And the ambiguity does not spread to the shapes that have no second reading: a
            // leading zero is not a thousands group in any locale, so this is an eighth of a
            // kilo and not 125 kg.
            assertEquals(125_000L, parseWeightMilligrams("0,125", UnitSystem.METRIC))
        }
        withLocale(Locale.FRANCE) {
            assertEquals(2_500_000L, parseWeightMilligrams("2,500", UnitSystem.METRIC))
            assertEquals(22_500_000L, parseWeightMilligrams("22.5", UnitSystem.METRIC))
            // France groups with a narrow no-break space, never with a dot — so `1.125` here is
            // an ASCII keyboard typing a decimal point, a 1.125 kg bar and not a 1125 kg one.
            assertEquals(1_125_000L, parseWeightMilligrams("1.125", UnitSystem.METRIC))
            assertEquals(125_000L, parseWeightMilligrams("0.125", UnitSystem.METRIC))
        }
    }

    /**
     * The distances worth checking in [unit] — a short one, a round one, and a long one.
     *
     * The short units get a half as well as whole values. Whole ones cannot fail a unit that
     * shows no decimals, so a list of only those would assert the round trip vacuously for
     * exactly the units whose round trip is in question.
     */
    private fun distancesIn(unit: DistanceUnit): List<Double> = when (unit.fractionDigits) {
        0 -> listOf(0.0, 25.0, 25.5, 100.0, 400.0, 1_609.0)
        else -> listOf(0.0, 0.25, 1.0, 5.25, 21.1, 42.19)
    }

    private fun forEachLocale(block: (Locale) -> Unit) {
        locales.forEach { locale -> withLocale(locale) { block(locale) } }
    }

    private fun forEachLocaleAndSystem(block: (Locale, UnitSystem) -> Unit) {
        forEachLocale { locale -> UnitSystem.entries.forEach { units -> block(locale, units) } }
    }

    private companion object {
        /** Half a step: the widest a value may sit from the grid and still be that value. */
        const val TOLERANCE_DIVISOR = 2.0
    }
}
