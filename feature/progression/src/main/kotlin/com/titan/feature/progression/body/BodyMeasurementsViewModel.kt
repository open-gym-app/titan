package com.titan.feature.progression.body

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.repository.BodyMeasurementRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.validation.BodyMeasurementValidator
import com.titan.core.ui.format.localDateOf
import com.titan.core.ui.state.savedScreenState
import com.titan.feature.progression.stats.ProgressionTimeframe
import com.titan.feature.progression.stats.StatsTimeframe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.LocalDate
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The body screen: what the user has measured, and the form that adds to it.
 *
 * ### One read, and the window is cut from it in memory
 *
 * Unlike the hub and the drill-down, which hand a date range to the database and re-subscribe
 * whenever it moves, this reads [BodyMeasurementRepository.getAll] once and slices it here.
 * The repository documents why it is unpaginated — a user measuring themselves weekly for a
 * decade has five hundred rows — and at that size the window is a filter over a list already
 * in memory. So there is no `flatMapLatest`, no per-window query, and the timeframe segments
 * answer on the frame they are tapped rather than at the speed of a query: none of the
 * stale-readings machinery `StatsViewModel` documents is needed, because there is no gap for
 * the readings to be stale across.
 *
 * ### All time opens on the first *measurement*, not the first session
 *
 * [StatsTimeframe.ALL] asks for the day the history starts, which on the other two screens is
 * the day the user first trained. Here it is the day they first stepped on the scale — a lifter
 * who trained for a year before they started weighing themselves would otherwise get an
 * all-time window with eleven empty months at the front of it. It comes off the history this
 * screen already holds, which is also why nothing here touches `ProgressionRepository`.
 *
 * ### The form is a value, not a destination
 *
 * Logging is a sheet over the screen rather than a push, for the reason `ExerciseFormSheet` is:
 * it is something you do *from* the screen and go straight back to. The form's own text fields
 * are its own — see `MeasurementFormSheet` — and only the finished entry comes back through
 * [BodyMeasurementsEvent.Saved], so a keystroke never travels through here and back.
 */
@HiltViewModel
class BodyMeasurementsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val timeframe: ProgressionTimeframe,
    private val repository: BodyMeasurementRepository,
    private val clock: Clock,
) : ViewModel() {

    /**
     * The entry being logged or corrected, or null when the sheet is closed.
     *
     * Saved, and it is the field that made #183 name this screen second: the form's own boxes
     * are `rememberSaveable` and come back on their own — but only if the sheet is *there* to
     * come back to, and whether it is open is this. Without it a user who left the app with six
     * figures typed would return to a closed sheet and an untouched history.
     *
     * The entry itself rather than a flag, because the sheet is keyed on it: the same value has
     * to come back, or `MeasurementFormSheet` re-seeds its fields from a different one.
     */
    private val form = savedStateHandle.savedScreenState(viewModelScope) { OpenForm() }

    /** Whether [save]'s write is still in flight. See [BodyMeasurementsUiState.isSaving]. */
    private val saving = MutableStateFlow(false)

    init {
        // The timeframe is shared with the hub and the drill-down, and outlives the process
        // through this handle — see `ProgressionTimeframe.restoreThrough`.
        timeframe.restoreThrough(savedStateHandle, viewModelScope)
    }

    val uiState: StateFlow<BodyMeasurementsUiState> = combine(
        timeframe.selected,
        repository.getAll(),
        form,
        saving,
    ) { selected, history, open, isSaving ->
        BodyMeasurementsUiState(
            timeframe = selected,
            isLoading = false,
            history = history,
            window = history.inside(selected),
            form = open.entry,
            isSaving = isSaving,
        )
    }.stateIn(
        scope = viewModelScope,
        // The other progression screens' number, for the same reasons: long enough to survive a
        // rotation without re-reading, short enough that a backgrounded screen is not holding a
        // database observer open.
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        // The shared selection rather than the enum's default: arriving from a hub set to `1Y`,
        // the selector would otherwise spend its first frame claiming ninety days. The open form
        // comes with it, so a restored sheet is drawn on the first frame rather than appearing
        // once the history read lands — a sheet that flickers shut is a sheet whose fields have
        // been thrown away, as far as `rememberSaveable` is concerned.
        initialValue = BodyMeasurementsUiState(
            timeframe = timeframe.selected.value,
            form = form.value.entry,
        ),
    )

    fun onEvent(event: BodyMeasurementsEvent) {
        when (event) {
            // Straight to the shared holder rather than to a local field: the hub reads the
            // same value, and a copy here is the copy that goes stale.
            is BodyMeasurementsEvent.TimeframeSelected -> timeframe.select(event.timeframe)

            BodyMeasurementsEvent.LogRequested -> form.value = OpenForm(prefilledEntry())

            is BodyMeasurementsEvent.EditRequested -> form.value = OpenForm(event.measurement)

            BodyMeasurementsEvent.FormDismissed -> form.value = OpenForm()

            is BodyMeasurementsEvent.Saved -> save(event.measurement)

            is BodyMeasurementsEvent.Deleted -> delete(event.id)
        }
    }

    /**
     * A new entry, dated now and carrying the last value of every figure.
     *
     * Pre-filled from [BodyMeasurementsUiState.readings] rather than from the newest *entry*,
     * which is the difference between a form that saves work and one that quietly loses it: a
     * user who logged weight alone yesterday would otherwise open a form with five empty
     * fields and have to retake a chest measurement they took last week. Everything is still
     * editable and everything is still optional — the point is that changing one figure is one
     * tap and a number.
     *
     * [BodyMeasurement.notes] is deliberately **not** carried over. A note annotates the
     * measurement it was written about; repeating it onto every subsequent entry would turn one
     * remark into a history of them.
     *
     * The readings come off [uiState] rather than [BodyMeasurementRepository.getLatest], which
     * exists for exactly this job: the whole history is already in hand here for the chart, so
     * a second query would be a round trip to re-read a row this screen is holding.
     */
    private fun prefilledEntry(): BodyMeasurement {
        val readings = uiState.value.readings
        val now = clock.nowMillis()

        return BodyMeasurement(
            id = NEW_ENTRY,
            weight = readings.weight,
            chest = readings.chest,
            waist = readings.waist,
            arm = readings.arm,
            thigh = readings.thigh,
            bodyFat = readings.bodyFat,
            notes = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Stores [measurement], or drops it when it breaks a rule.
     *
     * The form will not offer to save an entry the validator would refuse — it bounds each
     * field as it is typed and disables the button on an entry recording nothing — so this is
     * the last line rather than the one the user meets. It is still run, because
     * `BodyMeasurementValidator` is a check callers make rather than a gate the write path
     * forces them through, and the bounds simply do not hold for a row that skipped it.
     *
     * `updatedAt` is stamped here and `createdAt` is not: the entry's own date is when the
     * measurement was *taken*, which is the form's to set and the history's to order by, while
     * "when this row was last written" is nobody's business but the store's.
     *
     * **Closed on success only**, the rule `ExerciseDetailViewModel` states for its own sheet:
     * clearing the form first and then failing to write would take away the figures the user
     * has just typed and leave nothing on screen to say the entry was never stored. The form's
     * own text is keyed on the entry, so an unchanged entry means the boxes are still holding
     * what was typed when the sheet comes back.
     *
     * **And inert while the write is in flight**, which is the other half of that rule rather
     * than a separate precaution. A sheet that stays up until the store answers is a save
     * button that stays live until the store answers, and the second tap of a double-tap is a
     * second [BodyMeasurementRepository.insert] — two identical entries, both dated now, in a
     * history the user then has to prune. The guard is here as well as on the button because
     * the button is a *drawing* of this flag: an event that arrives anyway — a slow frame, a
     * key repeat, a test — is refused rather than merely discouraged. `SessionSummaryViewModel`
     * spells the same pair out for every field it keeps open across a write.
     */
    private fun save(measurement: BodyMeasurement) {
        if (!BodyMeasurementValidator.validate(measurement).isValid) return
        if (saving.value) return

        val stamped = measurement.copy(updatedAt = clock.nowMillis())
        saving.value = true

        viewModelScope.launch {
            val written =
                write { if (stamped.id == NEW_ENTRY) repository.insert(stamped) else repository.update(stamped) }

            saving.value = false
            if (written != null) form.value = OpenForm()
        }
    }

    /** Removes the entry [id] names. */
    private fun delete(id: Long) {
        viewModelScope.launch { write { repository.delete(id) } }
    }

    /**
     * Runs a store write, or reports that it failed.
     *
     * A write that escaped here would take the process down: nothing installs a handler on
     * [viewModelScope], so a disk error on a save the user can simply retry would be a crash —
     * the argument `ActiveSessionViewModel` spells out for every write in this app. What a
     * failure costs instead is the write; the flow underneath is the truth either way, so the
     * screen still shows what the store actually holds.
     *
     * @return [Unit] when the write went through, or null when it did not.
     */
    private suspend fun write(block: suspend () -> Unit): Unit? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    /**
     * The entries [selected] covers, oldest first.
     *
     * The receiver is newest-first, as the repository orders it, and a chart is read the other
     * way — so it is reversed once here rather than at each call site that wants a series.
     */
    private fun List<BodyMeasurement>.inside(selected: StatsTimeframe): List<BodyMeasurement> {
        val days = selected.period(today(), firstMeasurementDay())
        return asReversed().filter { localDateOf(it.createdAt) in days }
    }

    /**
     * The day the history opens on, for [StatsTimeframe.ALL], or null when there is none.
     *
     * The oldest entry, which on a newest-first list is the last one.
     */
    private fun List<BodyMeasurement>.firstMeasurementDay(): LocalDate? =
        lastOrNull()?.let { localDateOf(it.createdAt) }

    /**
     * The day it is, in the zone the user is measuring themselves in.
     *
     * The device's zone rather than UTC, and read when the window is derived rather than on a
     * timer — `StatsViewModel` spells out both halves, and they apply unchanged here.
     */
    private fun today(): LocalDate = localDateOf(clock.nowMillis())

    /**
     * The open form, as one saveable value.
     *
     * A wrapper around the nullable entry rather than the entry itself, because what is saved is
     * a screen's state and the screen's state is "the sheet is open on this" — which includes
     * being closed. It also keeps the encoding the same one every other screen uses; see
     * [savedScreenState].
     */
    @Serializable
    private data class OpenForm(val entry: BodyMeasurement? = null)

    private companion object {
        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds
    }
}
