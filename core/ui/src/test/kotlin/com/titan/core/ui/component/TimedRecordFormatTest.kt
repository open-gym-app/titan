package com.titan.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.titan.core.domain.model.PaceStyle
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PersonalRecordEntry
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.paceOf
import com.titan.core.ui.format.withLocale
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * The records a timed exercise earns (#225), as every screen that draws a record writes them:
 * the name, the figure, and what it beat.
 *
 * Read out of a composition rather than off a screen, because these are `@Composable` for their
 * string resources and their ambient unit system, and the assertion is about the string.
 */
@RunWith(RobolectricTestRunner::class)
class TimedRecordFormatTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `a run's best pace reads as a pace per kilometre, or per mile`() {
        val fiveK = record(PrType.BEST_PACE, distance = 500_000, duration = 1_634)

        val (metric, imperial) = read(
            UnitSystem.METRIC,
            UnitSystem.IMPERIAL,
        ) { fiveK.label() to fiveK.formattedValue() }

        assertEquals("Best pace" to "5:27 /km", metric)
        assertEquals("Best pace" to "8:46 /mi", imperial)
    }

    @Test
    fun `a ride's best pace is named and drawn as its top speed`() {
        val ride = record(PrType.BEST_PACE, distance = 4_000_000, duration = 4_800, style = PaceStyle.SPEED)

        val (metric, imperial) = read(UnitSystem.METRIC, UnitSystem.IMPERIAL) { ride.label() to ride.formattedValue() }

        assertEquals("Top speed" to "30.0 km/h", metric)
        assertEquals("Top speed" to "18.6 mph", imperial)
    }

    @Test
    fun `a swim's best pace reads per hundred metres, or per hundred yards`() {
        val swim = record(PrType.BEST_PACE, distance = 40_000, duration = 420, style = PaceStyle.SWIM_PACE)

        assertEquals(
            listOf("1:45 /100 m", "1:36 /100 yd"),
            read(UnitSystem.METRIC, UnitSystem.IMPERIAL) { swim.formattedValue() },
        )
    }

    @Test
    fun `a weighted hold is drawn as its two parts, never as the product`() {
        val hold = record(PrType.MAX_WEIGHTED_HOLD, weight = 20_000_000L * 90, duration = 90)

        val (line, tile) = read(UnitSystem.METRIC) {
            hold.formattedValue() to (hold.formattedFigure() to hold.recordUnit())
        }.single()

        assertEquals("1:30 · +20 kg", line)
        // A tile sets the time large and the load under it.
        assertEquals("1:30" to "+20 kg", tile)
    }

    @Test
    fun `a longest hold is a clock reading with nothing after it`() {
        val plank = record(PrType.MAX_DURATION, duration = 150)

        assertEquals(listOf("Longest" to "2:30"), read(UnitSystem.METRIC) { plank.label() to plank.formattedValue() })
    }

    @Test
    fun `a longer hold improved by the time it added`() {
        val entry = PersonalRecordEntry(
            record = record(PrType.MAX_DURATION, duration = 150),
            exerciseName = "Plank",
            previousValue = 120L,
            isRecent = true,
        )

        assertEquals(listOf("+0:30"), read(UnitSystem.METRIC) { entry.formattedImprovement() })
    }

    @Test
    fun `a faster run improved by its pace, with a minus, whatever its distance`() {
        // 5:10 /km over 10 km, after 5:27 /km over 5 km.
        val entry = PersonalRecordEntry(
            record = record(PrType.BEST_PACE, distance = 1_000_000, duration = 3_100),
            exerciseName = "Running, Outdoor",
            previousValue = paceOf(durationSeconds = 1_634, distanceCentimetres = 500_000),
            isRecent = true,
        )

        assertEquals(listOf("\u22120:17 /km"), read(UnitSystem.METRIC) { entry.formattedImprovement() })
    }

    @Test
    fun `a faster ride improved by its speed, with a plus`() {
        val entry = PersonalRecordEntry(
            record = record(PrType.BEST_PACE, distance = 4_000_000, duration = 4_800, style = PaceStyle.SPEED),
            exerciseName = "Bicycling",
            previousValue = paceOf(durationSeconds = 3_600, distanceCentimetres = 2_850_000),
            isRecent = true,
        )

        assertEquals(listOf("+1.5 km/h"), read(UnitSystem.METRIC) { entry.formattedImprovement() })
    }

    @Test
    fun `a weighted hold draws no improvement, since a product has no parts to show`() {
        val entry = PersonalRecordEntry(
            record = record(PrType.MAX_WEIGHTED_HOLD, weight = 20_000_000L * 90, duration = 90),
            exerciseName = "Plank",
            previousValue = 10_000_000L * 90,
            isRecent = true,
        )

        assertEquals(listOf(null), read(UnitSystem.METRIC) { entry.formattedImprovement() })
    }

    @Test
    fun `the kind on its own is named for both of its readings`() {
        assertEquals(listOf("Fastest"), read(UnitSystem.METRIC) { PrType.BEST_PACE.label() })
    }

    /**
     * [block]'s answer in each of [unitSystems], in the UK locale — composed side by side in one
     * composition, since a test rule takes one `setContent`.
     */
    private fun <T> read(
        vararg unitSystems: UnitSystem,
        block: @Composable () -> T,
    ): List<T> {
        val results = arrayOfNulls<Any?>(unitSystems.size).toMutableList()
        withLocale(Locale.UK) {
            composeTestRule.setContent {
                unitSystems.forEachIndexed { index, unitSystem ->
                    TitanTheme(unitSystem = unitSystem) { results[index] = block() }
                }
            }
            composeTestRule.waitForIdle()
        }
        @Suppress("UNCHECKED_CAST")
        return results as List<T>
    }

    private fun record(
        type: PrType,
        weight: Long? = null,
        distance: Long? = null,
        duration: Long? = null,
        style: PaceStyle = PaceStyle.PACE,
    ) = PersonalRecord(
        id = 1,
        exerciseId = 1,
        workoutSetId = 1,
        type = type,
        weight = weight,
        distance = distance,
        duration = duration,
        achievedAt = 0,
        paceStyle = style,
    )
}
