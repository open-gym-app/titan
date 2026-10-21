package com.titan.core.data.db

import com.titan.core.domain.backup.AutoBackupSchedule
import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.TemplateDraft
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.repository.AutoBackupRepository
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.TemplateDraftRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration

/*
 * The two stores an import touches that are *not* the database, in memory.
 *
 * `:core:domain` has fakes of both in its own test source set, but a test source set is not a
 * published artifact and `:core:data`'s tests cannot see it — so these are deliberate
 * duplicates rather than an oversight. They are kept as small as the round trip needs: what
 * `BackupRoundTripTest` asks of them is that the settings come back and that the draft was
 * cleared, not how either is stored. The real implementations are covered by
 * `UserPreferencesRepositoryImplTest` and `InProgressSessionRepositoryImplTest`.
 */

/** The settings store, in memory, with the same read-whole/write-one-at-a-time shape as the real one. */
class InMemoryUserPreferencesRepository : UserPreferencesRepository {

    private val preferences = MutableStateFlow(UserPreferences())

    override fun observe(): Flow<UserPreferences> = preferences

    override suspend fun setUnitSystem(unitSystem: UnitSystem) = write { it.copy(unitSystem = unitSystem) }

    override suspend fun setTheme(theme: ThemePreference) = write { it.copy(theme = theme) }

    override suspend fun setDefaultRestDuration(duration: Duration) = write { it.copy(defaultRestDuration = duration) }

    override suspend fun setBarWeight(barWeight: Long) = write { it.copy(barWeight = barWeight) }

    override suspend fun setPlateInventory(inventory: PlateInventory?) = write { it.copy(plateInventory = inventory) }

    override suspend fun setPlatePairs(
        weight: Long,
        pairs: Int,
    ) = write { it.copy(plateInventory = it.plates.withPairs(weight, pairs)) }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) = write { it.copy(trainingGoal = trainingGoal) }

    override suspend fun setFitnessLevel(fitnessLevel: FitnessLevel) = write { it.copy(fitnessLevel = fitnessLevel) }

    override suspend fun setCustomRepRange(range: RepRange) = write { it.copy(customRepRange = range) }

    override suspend fun setCustomIncrement(milligrams: Long?) = write { it.copy(customIncrement = milligrams) }

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) = write { it.copy(oneRepMaxFormula = formula) }

    override suspend fun setWarmupSuggestions(enabled: Boolean) = write { it.copy(warmupSuggestions = enabled) }

    override suspend fun setOnboardingCompleted(completed: Boolean) = write { it.copy(onboardingCompleted = completed) }

    override suspend fun setNotificationEnabled(
        kind: NotificationKind,
        enabled: Boolean,
    ) = write { it.copy(notifications = it.notifications.with(kind, enabled)) }

    override suspend fun setNotificationPermissionRequested() =
        write { it.copy(notifications = it.notifications.copy(permissionRequested = true)) }

    override suspend fun setReminderDay(
        day: DayOfWeek,
        enabled: Boolean,
    ) = write {
        val days = it.reminders.days
        it.copy(reminders = it.reminders.copy(days = if (enabled) days + day else days - day))
    }

    override suspend fun setReminderTime(time: LocalTime) =
        write { it.copy(reminders = it.reminders.copy(timeOfDay = time)) }

    override suspend fun clear() = write { UserPreferences() }

    private fun write(transform: (UserPreferences) -> UserPreferences) = preferences.update(transform)
}

/**
 * The auto-backup schedule, in memory, for driving `BackupWorker` without a DataStore.
 *
 * Whether the *store* keeps its promises is `AutoBackupRepositoryImplTest`'s question, over a
 * real file; what the worker's tests need from it is a flag to set and a record to read back.
 * The one behaviour reproduced faithfully is that a success clears the failure before it,
 * because the worker is the only thing that ever calls the two.
 */
class InMemoryAutoBackupRepository(enabled: Boolean = true) : AutoBackupRepository {

    private val schedule = MutableStateFlow(AutoBackupSchedule(enabled = enabled))

    /** When true, every write throws the way a store with no space does. */
    var writesFail = false

    /** When true, [read] throws the way a damaged or unreadable store does. */
    var readsFail = false

    val current: AutoBackupSchedule get() = schedule.value

    override fun observe(): Flow<AutoBackupSchedule> = schedule

    override suspend fun read(): AutoBackupSchedule {
        if (readsFail) throw IOException("the auto-backup store is not readable")
        return schedule.value
    }

    override suspend fun setEnabled(enabled: Boolean) = write { it.copy(enabled = enabled) }

    override suspend fun recordSuccess(at: Long) = write { it.copy(lastSuccessAt = at, lastFailureAt = null) }

    override suspend fun recordFailure(at: Long) = write { it.copy(lastFailureAt = at) }

    private fun write(transform: (AutoBackupSchedule) -> AutoBackupSchedule) {
        if (writesFail) throw IOException("the auto-backup store is not writable")
        schedule.update(transform)
    }
}

/** The in-progress draft, in memory, counting the clears an import is supposed to perform. */
class CountingInProgressSessionRepository : InProgressSessionRepository {

    private var session: ActiveSession? = null

    /** How many times [clear] has been called, including the ones that had nothing to clear. */
    var clearCount = 0
        private set

    override suspend fun save(session: ActiveSession) {
        this.session = session
    }

    override suspend fun load(): ActiveSession? = session

    override suspend fun clear() {
        clearCount++
        session = null
    }
}

/** The half-built template draft, in memory, counting the clears an import is supposed to perform. */
class CountingTemplateDraftRepository : TemplateDraftRepository {

    private var draft: TemplateDraft? = null

    /** How many times [clear] has been called, including the ones that had nothing to clear. */
    var clearCount = 0
        private set

    override suspend fun save(draft: TemplateDraft) {
        this.draft = draft
    }

    override suspend fun load(): TemplateDraft? = draft

    override suspend fun clear() {
        clearCount++
        draft = null
    }
}
