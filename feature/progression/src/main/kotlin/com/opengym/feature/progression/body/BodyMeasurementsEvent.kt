package com.opengym.feature.progression.body

import com.opengym.core.domain.model.BodyMeasurement
import com.opengym.feature.progression.stats.StatsTimeframe

/**
 * What the user can do to the body screen.
 *
 * The first progression screen that **writes**. The other two are readouts, so their events
 * only change what is being read; four of the six below change the history itself, which is
 * why this one has a form behind it at all.
 */
sealed interface BodyMeasurementsEvent {

    /**
     * A timeframe was tapped.
     *
     * It moves the window on the hub and the per-exercise drill-down too, because the selection
     * is shared — see [com.opengym.feature.progression.stats.ProgressionTimeframe].
     */
    data class TimeframeSelected(val timeframe: StatsTimeframe) : BodyMeasurementsEvent

    /**
     * The `+` in the top bar: open the form on a new entry.
     *
     * It carries nothing. What a new entry is pre-filled with is the ViewModel's to decide —
     * see [BodyMeasurementsViewModel.onEvent] — because "the last value of each figure" is a
     * reading of the whole history rather than something the button knows.
     */
    data object LogRequested : BodyMeasurementsEvent

    /** A history row was tapped: open the form on that entry. */
    data class EditRequested(val measurement: BodyMeasurement) : BodyMeasurementsEvent

    /** The form was dismissed without saving. */
    data object FormDismissed : BodyMeasurementsEvent

    /**
     * The form's save button, with the entry it assembled.
     *
     * The form hands over a whole [BodyMeasurement] rather than the fields it holds, because it
     * is the only thing that knows which unit system the figures were typed in — parsing is a
     * `LocalUnitSystem` reading, and it happens where that is ambient. See
     * [com.opengym.feature.progression.body.component.MeasurementFormSheet].
     */
    data class Saved(val measurement: BodyMeasurement) : BodyMeasurementsEvent

    /** A history row was swiped away. */
    data class Deleted(val id: Long) : BodyMeasurementsEvent
}
