package com.opengym.core.domain.backup

import com.opengym.core.domain.backup.model.PlateStockPayload
import com.opengym.core.domain.model.FitnessLevel
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.RepRange
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.plate.PlateStock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Settings out and back, and every way a stored value can be unreadable.
 *
 * The rest of the importer refuses a document over one bad row. This half deliberately does the
 * opposite and degrades to the documented default, for the reason `UserPreferencesRepository`'s
 * own reader does: a row is a record of something that happened, a setting is a fact the user
 * can change in two taps, and refusing a training history because a downgraded build does not
 * recognise a theme name is the wrong trade.
 */
class PreferencesMappingTest {

    @Test
    fun `settings survive a round trip unchanged`() {
        val preferences = UserPreferences(
            unitSystem = UnitSystem.IMPERIAL,
            theme = ThemePreference.DARK,
            defaultRestDuration = 120.seconds,
            barWeight = 45_000_000,
            plateInventory = PlateInventory.of(listOf(PlateStock(weight = 20_000_000, pairs = 4))),
            trainingGoal = TrainingGoal.HYPERTROPHY,
            fitnessLevel = FitnessLevel.ADVANCED,
            customRepRange = RepRange(6, 10),
            customIncrement = 1_250_000,
            oneRepMaxFormula = OneRepMaxFormula.BRZYCKI,
            onboardingCompleted = true,
        )

        assertEquals(preferences, preferences.toPayload().toPreferences())
    }

    @Test
    fun `a fresh install's defaults survive a round trip unchanged`() {
        val preferences = UserPreferences()

        assertEquals(preferences, preferences.toPayload().toPreferences())
    }

    /** Null is a value — "never chosen a rack" — and must not become an empty one. */
    @Test
    fun `an unchosen plate rack stays unchosen`() {
        val payload = UserPreferences(plateInventory = null).toPayload()

        assertNull(payload.plateInventory)
        assertNull(payload.toPreferences().plateInventory)
    }

    @Test
    fun `a chosen rack keeps its order and its counts`() {
        val rack = PlateInventory.of(
            listOf(PlateStock(weight = 10_000_000, pairs = 2), PlateStock(weight = 20_000_000, pairs = 4)),
        )

        val restored = UserPreferences(plateInventory = rack).toPayload().toPreferences().plateInventory

        assertEquals(rack, restored)
        assertEquals(listOf(20_000_000L, 10_000_000L), restored?.stock?.map { it.weight })
    }

    @Test
    fun `a unit system this build does not have falls back to metric`() {
        val payload = samplePreferences().copy(unitSystem = "FURLONGS")

        assertEquals(UnitSystem.METRIC, payload.toPreferences().unitSystem)
    }

    @Test
    fun `a theme this build does not have falls back to the system one`() {
        val payload = samplePreferences().copy(theme = "AMOLED")

        assertEquals(ThemePreference.SYSTEM, payload.toPreferences().theme)
    }

    @Test
    fun `a training goal this build does not have falls back to strength`() {
        val payload = samplePreferences().copy(trainingGoal = "LOOKING_GOOD")

        assertEquals(TrainingGoal.STRENGTH, payload.toPreferences().trainingGoal)
    }

    @Test
    fun `a fitness level this build does not have falls back to beginner`() {
        val payload = samplePreferences().copy(fitnessLevel = "ELITE")

        assertEquals(FitnessLevel.BEGINNER, payload.toPreferences().fitnessLevel)
    }

    /**
     * The key arrived after the first documents were written (#M7-11), so a file without it is
     * not malformed — it is a device that was never asked, and it restores as one.
     */
    @Test
    fun `a file written before the fitness level existed restores as a beginner`() {
        val payload = samplePreferences().copy(fitnessLevel = null)

        assertEquals(FitnessLevel.BEGINNER, payload.toPreferences().fitnessLevel)
    }

    @Test
    fun `the fitness level is written by name, so a file from this build carries it`() {
        val payload = UserPreferences(fitnessLevel = FitnessLevel.INTERMEDIATE).toPayload()

        assertEquals("INTERMEDIATE", payload.fitnessLevel)
    }

    // ─── The CUSTOM goal's range and step (#M7-15) ──────────────────────────────────────

    @Test
    fun `the custom range and step are written under the store's keys`() {
        val payload = UserPreferences(customRepRange = RepRange(6, 10), customIncrement = 1_250_000).toPayload()

        assertEquals(6, payload.customRepRangeMin)
        assertEquals(10, payload.customRepRangeMax)
        assertEquals(1_250_000L, payload.customIncrement)
    }

    @Test
    fun `a file written before the custom range existed restores the default range`() {
        val payload = samplePreferences().copy(customRepRangeMin = null, customRepRangeMax = null)

        assertEquals(RepRange.DEFAULT_CUSTOM, payload.toPreferences().customRepRange)
    }

    @Test
    fun `a custom range with one end missing restores the default range`() {
        assertEquals(
            RepRange.DEFAULT_CUSTOM,
            samplePreferences().copy(customRepRangeMax = null).toPreferences().customRepRange,
        )
        assertEquals(
            RepRange.DEFAULT_CUSTOM,
            samplePreferences().copy(customRepRangeMin = null).toPreferences().customRepRange,
        )
    }

    @Test
    fun `a custom range the model refuses restores the default range rather than throwing`() {
        // Ends the wrong way round, and a bound outside what a range may hold: both are what a
        // hand-edited file looks like, and neither may take a training history down with it.
        assertEquals(
            RepRange.DEFAULT_CUSTOM,
            samplePreferences().copy(customRepRangeMin = 12, customRepRangeMax = 8).toPreferences().customRepRange,
        )
        assertEquals(
            RepRange.DEFAULT_CUSTOM,
            samplePreferences().copy(customRepRangeMin = 0).toPreferences().customRepRange,
        )
    }

    /** Null is a value — "the rack's own step" — and must stay null rather than become a number. */
    @Test
    fun `an unset custom increment stays unset`() {
        val payload = UserPreferences(customIncrement = null).toPayload()

        assertNull(payload.customIncrement)
        assertNull(payload.toPreferences().customIncrement)
    }

    @Test
    fun `a custom increment of nothing restores as unset rather than throwing`() {
        assertNull(samplePreferences().copy(customIncrement = 0).toPreferences().customIncrement)
        assertNull(samplePreferences().copy(customIncrement = -1).toPreferences().customIncrement)
    }

    @Test
    fun `a one-rep-max formula this build does not have falls back to Epley`() {
        val payload = samplePreferences().copy(oneRepMaxFormula = "LOMBARDI")

        assertEquals(OneRepMaxFormula.EPLEY, payload.toPreferences().oneRepMaxFormula)
    }

    /**
     * `UserPreferences` throws on a rest under a second, so a payload holding zero has to be
     * caught before its constructor sees it — otherwise an import dies over a rest timer.
     */
    @Test
    fun `a rest of zero seconds falls back to the default rather than throwing`() {
        val payload = samplePreferences().copy(defaultRestDurationSeconds = 0)

        assertEquals(UserPreferences.DEFAULT_REST_DURATION, payload.toPreferences().defaultRestDuration)
    }

    @Test
    fun `a negative rest falls back to the default`() {
        val payload = samplePreferences().copy(defaultRestDurationSeconds = -30)

        assertEquals(UserPreferences.DEFAULT_REST_DURATION, payload.toPreferences().defaultRestDuration)
    }

    /**
     * The far end of the same bound. `Duration` saturates rather than overflowing, so a rest
     * past what it can hold becomes `INFINITE` — which `UserPreferences` refuses, and the
     * exception would escape an import that has already replaced every table.
     */
    @Test
    fun `a rest longer than a duration can hold falls back to the default`() {
        val payload = samplePreferences().copy(defaultRestDurationSeconds = Long.MAX_VALUE)

        assertEquals(UserPreferences.DEFAULT_REST_DURATION, payload.toPreferences().defaultRestDuration)
    }

    @Test
    fun `a bar of no weight falls back to the Olympic bar`() {
        val payload = samplePreferences().copy(barWeight = 0)

        assertEquals(UserPreferences.DEFAULT_BAR_WEIGHT, payload.toPreferences().barWeight)
    }

    @Test
    fun `a negative bar weight falls back to the Olympic bar`() {
        val payload = samplePreferences().copy(barWeight = -1)

        assertEquals(UserPreferences.DEFAULT_BAR_WEIGHT, payload.toPreferences().barWeight)
    }

    /**
     * An unreadable rack becomes *null*, not an empty one: null derives the standard rack for
     * the user's unit system, where an empty inventory is a barbell with nothing to load on it.
     */
    @Test
    fun `a plate outside the weights a plate may have leaves the rack unchosen`() {
        val payload = samplePreferences().copy(plateInventory = listOf(PlateStockPayload(weight = 1, pairs = 2)))

        assertNull(payload.toPreferences().plateInventory)
    }

    @Test
    fun `a plate count no rack could hold leaves the rack unchosen`() {
        val payload = samplePreferences().copy(
            plateInventory = listOf(PlateStockPayload(weight = 20_000_000, pairs = 999)),
        )

        assertNull(payload.toPreferences().plateInventory)
    }

    @Test
    fun `more denominations than an inventory admits leaves the rack unchosen`() {
        val tooMany = (1..PlateInventory.MAX_DENOMINATIONS + 1).map {
            PlateStockPayload(weight = it * 1_000_000L, pairs = 1)
        }

        assertNull(samplePreferences().copy(plateInventory = tooMany).toPreferences().plateInventory)
    }

    /** `PlateInventory.of` sorts and de-duplicates, so a rack out of order is repaired, not refused. */
    @Test
    fun `a rack written out of order is put back in order`() {
        val payload = samplePreferences().copy(
            plateInventory = listOf(
                PlateStockPayload(weight = 10_000_000, pairs = 2),
                PlateStockPayload(weight = 20_000_000, pairs = 4),
            ),
        )

        assertEquals(listOf(20_000_000L, 10_000_000L), payload.toPreferences().plateInventory?.stock?.map { it.weight })
    }

    /** A rest finer than a second cannot be expressed by the store, so it must not survive one. */
    @Test
    fun `a rest is carried to the second`() {
        val payload = UserPreferences(defaultRestDuration = 90.seconds).toPayload()

        assertEquals(90L, payload.defaultRestDurationSeconds)
    }

    /** The payload's keys are the store's keys, which PERSISTENCE.md calls a persisted format. */
    @Test
    fun `enum settings travel as their names`() {
        val payload = UserPreferences(unitSystem = UnitSystem.IMPERIAL, theme = ThemePreference.LIGHT).toPayload()

        assertEquals("IMPERIAL", payload.unitSystem)
        assertEquals("LIGHT", payload.theme)
    }
}
