package com.titan.feature.progression.body

import androidx.compose.runtime.Immutable
import com.titan.core.domain.model.BodyMeasurement
import com.titan.feature.progression.stats.StatsTimeframe

/**
 * Everything the body screen draws, as one value.
 *
 * ### Canonical figures, not formatted ones
 *
 * Milligrams, millimetres, tenths of a percent and epoch millis, exactly as the two other
 * progression screens carry their readings and for the same reason: formatting reads
 * `LocalUnitSystem`, which is ambient in the composition, so a ViewModel that formatted would
 * hold state that goes stale the moment the user switches to pounds. That is also what makes
 * #M5-15's "values update live when the unit system changes" true without anything here being
 * told about it.
 *
 * @param timeframe the window the weight series is read over. Shared with the hub and the
 *   per-exercise drill-down — see [com.titan.feature.progression.stats.ProgressionTimeframe].
 * @param isLoading true until the history has been read once. One flag, because there is one
 *   read: the repository hands over every entry and the window below is cut from it in memory,
 *   which is why changing the timeframe on this screen never goes back to the database.
 * @param history every entry, **newest first**, as the repository orders them. What the history
 *   list draws, and what every reading below is taken off.
 * @param window the entries inside [timeframe], **oldest first** — the direction a chart is
 *   read in.
 * @param form the entry being logged or corrected, or **null** when the sheet is closed. A new
 *   entry carries [NEW_ENTRY] as its id and the values it was pre-filled with; see
 *   [BodyMeasurementsViewModel].
 * @param isSaving the entry's write is in flight. The sheet stays up and its save button goes
 *   inert, the shape `SessionSummaryUiState` uses for every write it keeps a field open across:
 *   the form is closed on success only, so without this the button is live for as long as the
 *   store takes and a second tap logs the measurement twice.
 */
@Immutable
data class BodyMeasurementsUiState(
    val timeframe: StatsTimeframe = StatsTimeframe.DEFAULT,
    val isLoading: Boolean = true,
    val history: List<BodyMeasurement> = emptyList(),
    val window: List<BodyMeasurement> = emptyList(),
    val form: BodyMeasurement? = null,
    val isSaving: Boolean = false,
) {

    /**
     * The most recent value each figure was measured at, which is **not** the most recent
     * entry's fields.
     *
     * Every measurement is independently optional, so a user who stepped on the scale this
     * morning without reaching for the tape has a newest entry holding a weight and five
     * nulls. Reading the tiles off that entry would blank a chest measurement the user took
     * last week and has not been asked to retake — so each figure is carried down from
     * wherever it was last recorded. See [latestReadings].
     */
    val readings: BodyReadings = history.latestReadings()

    /**
     * The weight series the chart plots: the entries in the window that recorded one, oldest
     * first.
     *
     * Filtered rather than zero-filled. An entry that measured a waist and no weight is not a
     * week the user weighed nothing, and a line dropping to the floor on it would be the chart
     * inventing a crash diet.
     */
    val weights: List<BodyMeasurement> = window.filter { it.weight != null }

    /**
     * How far the weight moved across the window, in milligrams, or **null** with fewer than
     * two readings in it.
     *
     * Signed: negative is a loss, which on this screen is as likely to be the goal as a gain.
     * The chip draws the direction as an arrow and the magnitude unsigned — see `ChangeChip` —
     * so nothing downstream has to decide which way is good.
     *
     * First to last **within the window**, not against the period before it, which is the one
     * place this screen departs from the hub's hero: a body weight is a level rather than a
     * total, so "how much have I moved since this window opened" is the question, where
     * cumulative volume can only be compared against another period's sum.
     */
    val weightChange: Long? = weights
        .takeIf { it.size >= MIN_TREND_READINGS }
        ?.let { readings -> readings.last().weight!! - readings.first().weight!! }

    /**
     * Whether the user has logged nothing at all, ever.
     *
     * The one empty state this screen has. A *window* with nothing in it needs none: the hero
     * still has a weight to show — the last one recorded, whenever that was — and the chart
     * says "nothing to plot yet" in its own placeholder rather than in a screen-sized line.
     * That is the difference between this screen and the hub, whose every figure is a sum over
     * the window and so has nothing whatever to say about an empty one.
     */
    val isEmpty: Boolean = !isLoading && history.isEmpty()
}

/**
 * The latest reading of each figure, carried down from wherever in the history it was recorded.
 *
 * A type of its own rather than a synthesised [BodyMeasurement], because it is not an entry:
 * its fields can come off six different dates, and handing that to anything expecting a row —
 * the form, the history list, an export — would be a measurement set nobody ever took.
 *
 * @param leanMass the last lean mass that could be *computed*, which is a stricter thing than
 *   the last weight and the last body fat. Both have to come off the same entry — see
 *   [BodyMeasurement.leanMass] — so pairing this month's weight with a body-fat figure from
 *   March would report a lean mass the user has never had.
 */
@Immutable
data class BodyReadings(
    val weight: Long? = null,
    val chest: Int? = null,
    val waist: Int? = null,
    val arm: Int? = null,
    val thigh: Int? = null,
    val bodyFat: Int? = null,
    val leanMass: Long? = null,
) {

    /** Whether any circumference has ever been measured — what the parts card is drawn for. */
    val hasCircumference: Boolean = chest != null || waist != null || arm != null || thigh != null
}

/** [BodyReadings] off a **newest-first** history. See [BodyMeasurementsUiState.readings]. */
fun List<BodyMeasurement>.latestReadings(): BodyReadings =
    BodyReadings(
        weight = firstNotNullOfOrNull(BodyMeasurement::weight),
        chest = firstNotNullOfOrNull(BodyMeasurement::chest),
        waist = firstNotNullOfOrNull(BodyMeasurement::waist),
        arm = firstNotNullOfOrNull(BodyMeasurement::arm),
        thigh = firstNotNullOfOrNull(BodyMeasurement::thigh),
        bodyFat = firstNotNullOfOrNull(BodyMeasurement::bodyFat),
        leanMass = firstNotNullOfOrNull(BodyMeasurement::leanMass),
    )

/**
 * The id a [BodyMeasurement] that has not been stored yet carries.
 *
 * Zero, because the store assigns ids from one — the same sentinel `ExerciseProgressionDestination`
 * uses, and the reason `BodyMeasurement.id` defaults to it.
 */
const val NEW_ENTRY: Long = 0L

/**
 * How many readings a weight trend needs: two.
 *
 * The same figure `:core:ui` refuses to plot below, and for the same reason — one weigh-in is
 * not a direction. Named here as well because the *chip* is not the chart: a change computed
 * off a single reading would be zero, and a chip claiming "no change" to somebody who has
 * weighed themselves once is a comparison that was never made.
 */
private const val MIN_TREND_READINGS = 2
