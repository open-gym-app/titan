package com.opengym.feature.progression.exercise

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseProgression
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.ProgressionRepository
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.usecase.ExerciseProgressionUseCase
import com.opengym.core.ui.format.localDateOf
import com.opengym.feature.progression.navigation.ExerciseProgressionDestination
import com.opengym.feature.progression.navigation.NO_EXERCISE
import com.opengym.feature.progression.stats.ProgressionTimeframe
import com.opengym.feature.progression.stats.StatsTimeframe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * One lift, over the window the progression screens share: its bests, its trend, and what it
 * has been doing session by session.
 *
 * ### Which lift is a value, not a route argument
 *
 * The route argument is only where it *starts*. The screen's own chooser is what a user opened
 * from the hub lands on, and it is also the switcher a user who arrived from an exercise's own
 * page gets in the top bar — so the selection has to be able to move without navigating. A
 * push per lift would fill the back stack with screens somebody was flicking through.
 *
 * ### The window is the hub's, and it is not snapped
 *
 * [ProgressionTimeframe] is shared with #32, so the two screens cannot disagree about what
 * "90 days" means. Unlike the hub, the period handed to the reads is **not** widened to whole
 * weeks: an attempt is a session on a date rather than a weekly bucket, so there is no partial
 * bucket to round out — the days the user asked for are exactly the sessions they get. The one
 * thing that still needs the whole-history read is [StatsTimeframe.ALL], whose period opens on
 * the day the user first trained.
 *
 * ### Four reads, one loading flag
 *
 * `combine` holds its first emission until every source has answered, so the screen never
 * draws a half-assembled window. `flatMapLatest` is what tears the per-lift reads down and
 * re-subscribes when the lift or the window changes; the old selection's rows are dropped
 * rather than raced against the new one's.
 *
 * ### Neither control waits for the database
 *
 * The timeframe segments and the chooser both answer at the speed of a thumb, and the readings
 * answer at the speed of a query. So [uiState] takes both from their own sources and compares
 * them against the stamp the readings carry: while they disagree the screen is *loading*
 * rather than showing the previous lift's numbers under the new lift's name. `StatsViewModel`
 * documents the same arrangement for the timeframe alone; here it matters twice over, because
 * relabelling one exercise's records with another's name is a worse lie than relabelling a
 * window.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseProgressionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val timeframe: ProgressionTimeframe,
    private val progression: ExerciseProgressionUseCase,
    private val progressionRepository: ProgressionRepository,
    private val exerciseRepository: ExerciseRepository,
    private val clock: Clock,
) : ViewModel() {

    /**
     * The lift on screen, or null for the chooser.
     *
     * Seeded from the route and moved by [ExerciseProgressionEvent.ExerciseSelected]. The
     * sentinel is translated once, here, so nothing below this line has to know that zero
     * means "nobody named one" — see [ExerciseProgressionDestination].
     */
    private val selected = MutableStateFlow(
        savedStateHandle.toRoute<ExerciseProgressionDestination>()
            .exerciseId
            .takeIf { it != NO_EXERCISE },
    )

    /**
     * The window every series is read over, and the timeframe it came from.
     *
     * Deduplicated for the reason the hub deduplicates it: `getFirstTrainingDay` re-emits
     * whenever a session row changes and only [StatsTimeframe.ALL] is a function of it, so
     * without this, finishing a workout would tear down and rebuild the reads on a screen
     * showing the last ninety days, whose window did not move.
     */
    private val window: Flow<Window> = combine(
        timeframe.selected,
        progressionRepository.getFirstTrainingDay(),
    ) { selectedTimeframe, firstTrainingDay ->
        Window(
            timeframe = selectedTimeframe,
            days = selectedTimeframe.period(today(), firstTrainingDay),
        )
    }.distinctUntilChanged()

    /** The screen, under the lift and the window the user has actually chosen. */
    val uiState: StateFlow<ExerciseProgressionUiState> = combine(
        timeframe.selected,
        selected,
        progressionRepository.getTrainedExercises(),
        combine(selected, window, ::Selection).flatMapLatest(::readings),
    ) { selectedTimeframe, selectedExercise, choices, readings ->
        val isStale = readings.timeframe != selectedTimeframe || readings.exerciseId != selectedExercise
        if (isStale) {
            // The selection travels even though the readings do not: it is what the selector is
            // drawn on, and a control that disappeared for the length of the query it started
            // would be the screen answering a tap by taking the tapped thing away.
            ExerciseProgressionUiState(
                timeframe = selectedTimeframe,
                choices = choices,
                isExerciseSelected = selectedExercise != null,
            )
        } else {
            ExerciseProgressionUiState(
                timeframe = selectedTimeframe,
                isLoading = false,
                exercise = readings.exercise,
                choices = choices,
                progression = readings.progression,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        // The other screens' number, for the same reasons: long enough to survive a rotation
        // without re-running the reads, short enough that a backgrounded screen is not holding
        // database observers open.
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        // The shared selection rather than the enum's default: arriving from a hub set to `1Y`,
        // the selector would otherwise spend its first frame claiming ninety days.
        initialValue = ExerciseProgressionUiState(timeframe = timeframe.selected.value),
    )

    fun onEvent(event: ExerciseProgressionEvent) {
        when (event) {
            // Straight to the shared holder rather than to a local field: the hub reads the
            // same value, and a copy here is the copy that goes stale.
            is ExerciseProgressionEvent.TimeframeSelected -> timeframe.select(event.timeframe)

            is ExerciseProgressionEvent.ExerciseSelected -> selected.value = event.exerciseId

            ExerciseProgressionEvent.ExerciseCleared -> selected.value = null
        }
    }

    /**
     * The reads for one lift over one window, stamped with both.
     *
     * With no lift chosen there is nothing to read: the chooser is drawn from
     * [ProgressionRepository.getTrainedExercises], which is subscribed to whatever the
     * selection is, so this branch emits an answer immediately rather than leaving the outer
     * `combine` waiting for a query nobody asked for.
     */
    private fun readings(selection: Selection): Flow<Readings> {
        val exerciseId = selection.exerciseId
            ?: return flowOf(Readings(timeframe = selection.window.timeframe, exerciseId = null))

        return combine(
            exerciseRepository.getById(exerciseId),
            progression(exerciseId, selection.window.days),
        ) { exercise, readings ->
            Readings(
                timeframe = selection.window.timeframe,
                exerciseId = exerciseId,
                // Null for an id that no longer resolves — a custom exercise deleted from the
                // Library while this screen sat on the back stack. The screen draws the chooser
                // for that, which is the one useful thing to offer somebody whose lift is gone.
                exercise = exercise,
                progression = readings.takeIf { exercise != null },
            )
        }
    }

    /**
     * The day it is, in the zone the user is training in.
     *
     * The device's zone rather than UTC, and read when the window is derived rather than on a
     * timer — `StatsViewModel` spells out both halves, and they apply unchanged here.
     */
    private fun today(): LocalDate = localDateOf(clock.nowMillis())

    /** A timeframe and the days it resolved to. */
    private data class Window(
        val timeframe: StatsTimeframe,
        val days: ClosedRange<LocalDate>,
    )

    /** What the reads are taken for: a lift, and a window. */
    private data class Selection(
        val exerciseId: Long?,
        val window: Window,
    )

    /** What the reads came back with, stamped with what they were taken for. */
    private data class Readings(
        val timeframe: StatsTimeframe,
        val exerciseId: Long?,
        val exercise: Exercise? = null,
        val progression: ExerciseProgression? = null,
    )

    private companion object {
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
