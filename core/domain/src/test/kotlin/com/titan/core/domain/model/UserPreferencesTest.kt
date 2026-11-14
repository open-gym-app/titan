package com.titan.core.domain.model

import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.plate.PlateStock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The defaults a fresh install reads, and the values the model refuses.
 *
 * The defaults are asserted one by one rather than as a single `assertEquals` against a
 * hand-built copy, because that form passes when the expectation and the model change
 * together — which is precisely the change worth catching. Every number here is a
 * product decision that a screen will show to a user who has never opened settings.
 */
class UserPreferencesTest {

    @Test
    fun `a fresh install reads metric units`() {
        assertEquals(UnitSystem.METRIC, UserPreferences().unitSystem)
    }

    @Test
    fun `a fresh install follows the system theme`() {
        // Not LIGHT: "follow the device" is the only default that is never wrong.
        assertEquals(ThemePreference.SYSTEM, UserPreferences().theme)
    }

    @Test
    fun `a fresh install rests three minutes`() {
        assertEquals(180.seconds, UserPreferences.DEFAULT_REST_DURATION)
        assertEquals(180.seconds, UserPreferences().defaultRestDuration)
    }

    @Test
    fun `a fresh install assumes a twenty kilo olympic bar`() {
        // Milligrams, matching WorkoutSet.weight — 20 kg is 20 million of them.
        assertEquals(20_000_000L, UserPreferences.DEFAULT_BAR_WEIGHT)
        assertEquals(20_000_000L, UserPreferences().barWeight)
    }

    @Test
    fun `a fresh install has a training goal but has not been onboarded`() {
        assertEquals(TrainingGoal.STRENGTH, UserPreferences().trainingGoal)
        assertFalse(UserPreferences().onboardingCompleted)
    }

    @Test
    fun `a fresh install is a beginner`() {
        // The most cautious band: somebody who skipped the question gets suggestions that go
        // up slowly rather than ones that assume years under the bar.
        assertEquals(FitnessLevel.BEGINNER, UserPreferences().fitnessLevel)
    }

    @Test
    fun `a fresh install has the hypertrophy range on file for CUSTOM and no step of its own`() {
        assertEquals(RepRange.DEFAULT_CUSTOM, UserPreferences().customRepRange)
        // Null, not a number: the step follows the rack until the user says otherwise.
        assertNull(UserPreferences().customIncrement)
    }

    @Test
    fun `the rep range is the goal's, and the user's own only under CUSTOM`() {
        val typed = RepRange(6, 10)

        assertEquals(
            RepRange.STRENGTH,
            UserPreferences(trainingGoal = TrainingGoal.STRENGTH, customRepRange = typed).repRange,
        )
        assertEquals(RepRange.HYPERTROPHY, UserPreferences(trainingGoal = TrainingGoal.HYPERTROPHY).repRange)
        assertEquals(RepRange.ENDURANCE, UserPreferences(trainingGoal = TrainingGoal.ENDURANCE).repRange)
        assertEquals(typed, UserPreferences(trainingGoal = TrainingGoal.CUSTOM, customRepRange = typed).repRange)
    }

    @Test
    fun `a custom increment of nothing is refused, and absent is allowed`() {
        assertThrows(IllegalArgumentException::class.java) { UserPreferences(customIncrement = 0) }
        assertThrows(IllegalArgumentException::class.java) { UserPreferences(customIncrement = -1) }
        assertNull(UserPreferences(customIncrement = null).customIncrement)
    }

    @Test
    fun `a rest duration of zero or less is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            UserPreferences(defaultRestDuration = Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UserPreferences(defaultRestDuration = (-1).seconds)
        }
    }

    @Test
    fun `an infinite rest duration is refused`() {
        // Positive, and still not a rest anybody could sit through. The countdown would
        // never publish a tick, so the sheet would sit at "infinity" forever.
        assertThrows(IllegalArgumentException::class.java) {
            UserPreferences(defaultRestDuration = Duration.INFINITE)
        }
    }

    @Test
    fun `a rest longer than a day is refused, and a day is allowed`() {
        assertThrows(IllegalArgumentException::class.java) {
            UserPreferences(defaultRestDuration = UserPreferences.MAX_REST_DURATION + 1.seconds)
        }
        assertEquals(
            UserPreferences.MAX_REST_DURATION,
            UserPreferences(defaultRestDuration = UserPreferences.MAX_REST_DURATION).defaultRestDuration,
        )
    }

    @Test
    fun `a bar or a step heavier than the heaviest set is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            UserPreferences(barWeight = UserPreferences.MAX_BAR_WEIGHT + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UserPreferences(customIncrement = UserPreferences.MAX_CUSTOM_INCREMENT + 1)
        }
    }

    @Test
    fun `a rest duration that is not whole seconds is refused`() {
        // The invariant that keeps this class and UserPreferencesRepository in step: the
        // store's resolution is a second, so a value with a fraction in it is one the
        // setter cannot persist. Admitting it here would mean a preferences object that
        // constructs cleanly and then throws on its way back to disk.
        assertThrows(IllegalArgumentException::class.java) {
            UserPreferences(defaultRestDuration = 500.milliseconds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UserPreferences(defaultRestDuration = 90_500.milliseconds)
        }

        // The boundary on the other side of it: one whole second is a rest.
        assertEquals(1.seconds, UserPreferences(defaultRestDuration = 1.seconds).defaultRestDuration)
    }

    @Test
    fun `a fresh install has said nothing about its plates`() {
        // Null rather than a rack, which is what makes the derived value below follow the
        // unit system instead of freezing whichever one happened to be the default.
        assertNull(UserPreferences().plateInventory)
    }

    @Test
    fun `the rack to load from follows the unit system until the user says otherwise`() {
        assertEquals(PlateInventory.METRIC, UserPreferences().plates)
        assertEquals(
            PlateInventory.IMPERIAL,
            UserPreferences(unitSystem = UnitSystem.IMPERIAL).plates,
        )
    }

    @Test
    fun `a stored rack wins over the unit system's default`() {
        // The half that matters after an edit: plates are physical, so reading weights in
        // pounds does not turn a metric gym's 20 kg plates into 45 lb ones.
        val mine = PlateInventory.of(listOf(PlateStock(weight = 20_000_000, pairs = 2)))

        assertEquals(
            mine,
            UserPreferences(unitSystem = UnitSystem.IMPERIAL, plateInventory = mine).plates,
        )
    }

    @Test
    fun `a bar weight of zero or less is refused`() {
        assertThrows(IllegalArgumentException::class.java) { UserPreferences(barWeight = 0) }
        assertThrows(IllegalArgumentException::class.java) { UserPreferences(barWeight = -1) }
    }

    @Test
    fun `copy carries every other setting through unchanged`() {
        // The shape the repository's setters rely on: one field named, five untouched.
        val stored = UserPreferences(
            unitSystem = UnitSystem.IMPERIAL,
            theme = ThemePreference.DARK,
            defaultRestDuration = 120.seconds,
            barWeight = 15_000_000,
            trainingGoal = TrainingGoal.HYPERTROPHY,
            onboardingCompleted = true,
        )

        assertEquals(
            UserPreferences(
                unitSystem = UnitSystem.METRIC,
                theme = ThemePreference.DARK,
                defaultRestDuration = 120.seconds,
                barWeight = 15_000_000,
                trainingGoal = TrainingGoal.HYPERTROPHY,
                onboardingCompleted = true,
            ),
            stored.copy(unitSystem = UnitSystem.METRIC),
        )
    }

    @Test
    fun `the enums cover exactly the documented choices`() {
        // Persisted by name (see UserPreferencesRepositoryImpl), so a rename is a
        // stored-value migration and not a refactor. This is where that shows up.
        assertEquals(listOf("METRIC", "IMPERIAL"), UnitSystem.entries.map { it.name })
        assertEquals(listOf("LIGHT", "DARK", "SYSTEM"), ThemePreference.entries.map { it.name })
        assertEquals(
            listOf("STRENGTH", "HYPERTROPHY", "ENDURANCE", "CUSTOM"),
            TrainingGoal.entries.map { it.name },
        )
        assertEquals(listOf("BEGINNER", "INTERMEDIATE", "ADVANCED"), FitnessLevel.entries.map { it.name })
    }
}
