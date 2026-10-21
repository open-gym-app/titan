package com.titan.feature.progression.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.model.ConsistencyTrend
import com.titan.core.domain.model.MuscleTraining
import com.titan.core.domain.model.RecordTally
import com.titan.core.domain.model.VolumeTrend
import com.titan.core.domain.repository.PersonalRecordRepository
import com.titan.core.domain.repository.ProgressionRepository
import com.titan.core.domain.stats.TrainingWeek
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.ConsistencyUseCase
import com.titan.core.domain.usecase.VolumeTrendUseCase
import com.titan.core.ui.format.localDateOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The stats hub: what the selected window added up to, and what it was spent on.
 *
 * ### The window is a source, and every read shares it
 *
 * The timeframe is [ProgressionTimeframe]'s rather than this class's, because #102 reads over
 * the same one. It is combined with the day the user's history starts — which only [ALL] uses,
 * and which no other reading of the screen could supply — into one **snapped** period, and
 * that period is then handed to all five reads. Snapping here rather than letting each read
 * snap for itself is the point: [VolumeTrendUseCase] and [ConsistencyUseCase] widen a period
 * to whole weeks on their own, and the three period reads below do not, so a hub whose muscle
 * map covered thirty days beside a chart covering five weeks would be two answers to one
 * question.
 *
 * ### Five reads, one loading flag
 *
 * `combine` holds its first emission until every source has answered, so
 * [StatsUiState.isLoading] is one flag rather than one per query and the screen never draws a
 * half-assembled period. `flatMapLatest` is what tears the five down and re-subscribes when
 * the timeframe changes; the old window's rows are dropped rather than raced against the new
 * one's.
 *
 * ### The selection is not one of the five reads
 *
 * [uiState] takes the timeframe from the holder directly rather than from the readings the
 * five queries assemble. The readings answer at the speed of the database; the selector has to
 * answer at the speed of a thumb. See [uiState] for what a disagreement between the two means.
 *
 * ### What "empty" is decided from
 *
 * The **consistency** series, not the volume one. A window can hold sessions and no volume at
 * all — an all-cardio fortnight, a week of bodyweight holds — and keying the empty state off
 * volume would tell somebody who trained six times that they had not trained. Consistency
 * counts finished sessions, which is the question "did anything happen in this window" asks.
 *
 * Which of the two empty states applies is [ConsistencyTrend.hasEverTrained]'s to answer, and
 * only it can: a long enough lapse empties the comparison period too, so nothing inside the
 * window separates a returning lifter from a new one.
 *
 * ### The day is read when the window is derived, and not on a timer
 *
 * Unlike Home, which draws a ring around *today* and would be visibly wrong at midnight, this
 * screen draws a window ninety days wide. A phone left on it overnight reads a window one day
 * stale — the same ninety weeks' worth of training, shifted by a day, inside a period that is
 * snapped to whole weeks anyway — and it is re-derived the moment the screen is subscribed to
 * again, which is every time the tab is opened. A midnight timer for that would be a wake-up a
 * day to move a boundary the snapping has already rounded away.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val timeframe: ProgressionTimeframe,
    private val volumeTrend: VolumeTrendUseCase,
    private val consistency: ConsistencyUseCase,
    private val progressionRepository: ProgressionRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val clock: Clock,
) : ViewModel() {

    init {
        // The selection is the singleton's, and outliving the process is this handle's — see
        // `ProgressionTimeframe.restoreThrough`. Whichever progression screen is rebuilt first
        // puts the shared selection back; a fresh launch finds nothing and opens on the default.
        timeframe.restoreThrough(savedStateHandle, viewModelScope)
    }

    /**
     * The window every read below is taken over, and the timeframe it came from.
     *
     * Deduplicated, because [ProgressionRepository.getFirstTrainingDay] re-emits whenever a
     * session row changes and only [StatsTimeframe.ALL] is a function of it — without this,
     * finishing a workout would tear down and rebuild all five reads on a screen showing the
     * last ninety days, whose window did not move.
     */
    private val window: Flow<Window> = combine(
        timeframe.selected,
        progressionRepository.getFirstTrainingDay(),
    ) { selected, firstTrainingDay ->
        Window(
            timeframe = selected,
            days = TrainingWeek.snap(selected.period(today(), firstTrainingDay)),
        )
    }.distinctUntilChanged()

    /**
     * The screen: the readings, under the timeframe the user has actually chosen.
     *
     * ### Why the selection is combined in rather than read off the readings
     *
     * `assemble` stamps each state with the window it was read over, and that stamp only lands
     * once all five queries have answered — a `1Y` or `ALL` window over a season of training is
     * long enough to see. Drawing the selector from it left the tapped segment unlit and the
     * figures unchanged for the whole of that wait, which is a control that looks dead. Here
     * the segment lights on the tap, because [ProgressionTimeframe.selected] is a `StateFlow`
     * and moves the instant [onEvent] writes it.
     *
     * ### And why a mismatch is the loading state rather than the old figures
     *
     * Once the selector says `1Y`, every figure under it is a claim about a year. Keeping the
     * previous window's numbers there would relabel ninety days as twelve months for as long as
     * the reads take — a wrong answer, where the loading state is merely a slow one. The screen
     * already draws the selector and nothing else while it waits; this is that same state,
     * reached a second way.
     *
     * The comparison is on the readings' own stamp rather than on a flag set when the tap
     * happens, so nothing has to be reset when the answer arrives: the state stops being stale
     * exactly when it stops being about the wrong window.
     */
    val uiState: StateFlow<StatsUiState> = combine(
        timeframe.selected,
        window.flatMapLatest(::readings),
    ) { selected, readings ->
        if (readings.timeframe == selected) readings else StatsUiState(timeframe = selected)
    }.stateIn(
        scope = viewModelScope,
        // The other screens' number, for the same reasons: long enough to survive a rotation
        // without re-running five queries, short enough that a backgrounded tab is not holding
        // database observers open.
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        // The shared selection rather than the enum's default: arriving from #102's drill-down
        // on `1Y`, the hub would otherwise spend its first frame claiming ninety days. Nothing
        // is emitted until the reads answer, so this value is what the selector is drawn from
        // until they do — and re-subscribing after the timeout keeps the last assembled state
        // rather than flashing back through a loading frame.
        initialValue = StatsUiState(timeframe = timeframe.selected.value),
    )

    fun onEvent(event: StatsEvent) {
        when (event) {
            // Straight to the shared holder rather than to a local field: the drill-down reads
            // the same value, and a copy here is the copy that goes stale.
            is StatsEvent.TimeframeSelected -> timeframe.select(event.timeframe)
        }
    }

    /** The five reads over one window, assembled. */
    private fun readings(window: Window): Flow<StatsUiState> =
        combine(
            volumeTrend(window.days),
            consistency(window.days),
            progressionRepository.getMuscleTraining(window.days),
            personalRecordRepository.getRecordTally(window.days),
            progressionRepository.getTrainingMillis(window.days),
        ) { volume, consistency, muscles, records, trainingMillis ->
            assemble(
                timeframe = window.timeframe,
                volume = volume,
                consistency = consistency,
                muscles = muscles,
                records = records,
                trainingMillis = trainingMillis,
            )
        }

    /**
     * The state, with the empty cases resolved first.
     *
     * A window with no sessions in it draws no hero, no grid and no map — null rather than
     * zeroed, which is #32's criterion that a brand-new user never sees a chart of noughts.
     * The muscle list is passed through regardless because it is already empty in that case;
     * a period with sessions in it always has something to say about what they worked.
     */
    @Suppress("LongParameterList")
    private fun assemble(
        timeframe: StatsTimeframe,
        volume: VolumeTrend,
        consistency: ConsistencyTrend,
        muscles: List<MuscleTraining>,
        records: RecordTally,
        trainingMillis: Long,
    ): StatsUiState {
        val emptiness = when {
            consistency.isFirstRun -> StatsEmptiness.FIRST_RUN
            consistency.isQuietPeriod -> StatsEmptiness.QUIET_PERIOD
            else -> null
        }

        return StatsUiState(
            timeframe = timeframe,
            isLoading = false,
            emptiness = emptiness,
            volume = if (emptiness != null) {
                null
            } else {
                VolumeState(
                    total = volume.totalVolume,
                    weeks = volume.weeks,
                    changeRatio = volume.volumeChangeRatio,
                )
            },
            summary = if (emptiness != null) {
                null
            } else {
                SummaryState(
                    sessions = consistency.totalSessions,
                    averagePerWeek = consistency.averagePerWeek,
                    averageChange = consistency.changePerWeek,
                    records = records,
                    trainingMillis = trainingMillis,
                )
            },
            muscles = muscles,
        )
    }

    /**
     * The day it is, in the zone the user is training in.
     *
     * The device's zone rather than UTC, which is what the buckets are cut in: the *window* is
     * chosen in the user's own days — "the last ninety days" ends today, wherever they are —
     * and the snapping to whole weeks then hands the aggregates a boundary they can bucket.
     * `TrainingWeek` documents the UTC approximation the buckets themselves carry, and
     * [localDateOf] is the same call Home groups its sessions into calendar days with.
     */
    private fun today(): LocalDate = localDateOf(clock.nowMillis())

    /** A timeframe and the whole weeks it resolved to. */
    private data class Window(
        val timeframe: StatsTimeframe,
        val days: ClosedRange<LocalDate>,
    )

    private companion object {
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
