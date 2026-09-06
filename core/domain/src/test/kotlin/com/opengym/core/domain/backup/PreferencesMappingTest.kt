package com.opengym.core.domain.backup

import com.opengym.core.domain.backup.model.PlateStockPayload
import com.opengym.core.domain.model.OneRepMaxFormula
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
