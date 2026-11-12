package com.titan.feature.settings.backup

import com.titan.core.domain.backup.AutoBackupSchedule
import com.titan.core.domain.backup.AutoBackupScheduler
import com.titan.core.domain.backup.model.BackupRow
import com.titan.core.domain.backup.model.BackupTable
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.PreferencesPayload
import com.titan.core.domain.backup.model.WorkoutSessionRow
import com.titan.core.domain.backup.toPayload
import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.DataFootprint
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.TemplateDraft
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.ReminderScheduler
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.repository.AutoBackupRepository
import com.titan.core.domain.repository.BackupRepository
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.LocalDataRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.repository.TemplateDraftRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration

/**
 * Everything `BackupViewModel` is constructed from, in memory.
 *
 * ### Why the storage is faked and the use cases are not
 *
 * `:feature:settings` may depend on `:core:domain` and `:core:ui` and nothing else — see
 * docs/ARCHITECTURE.md — so a real `BackupRepositoryImpl` over a real Room database is not
 * reachable from here, and should not be: whether the *rows* survive a round trip is
 * `BackupRoundTripTest`'s question, over a real SQLite, in the module that owns it.
 *
 * What is left for this module is the bridge — a scratch file renamed into place, a content
 * URI read back, an `ImportError` turned into a sentence — so the *use cases* are the real
 * ones and the *files* are real, and only the store underneath them is a fake. An export
 * driven by this backend produces a genuine document that a genuine importer reads back.
 *
 * Deliberate duplicates of `:core:domain`'s own test doubles, which are equally unreachable: a
 * test source set is not a published artifact.
 */
class BackupTestBackend {

    private val stored = MutableStateFlow(EMPTY_DOCUMENT)

    val preferences = InMemoryUserPreferences()
    val draft = RecordingDraftStore()
    val templateDraft = RecordingTemplateDraftStore()
    val sessionState = InMemorySessionState()
    val autoBackup = InMemoryAutoBackupSchedule()
    val scheduler = RecordingAutoBackupScheduler()
    val reminders = RecordingReminderScheduler()

    /** The document currently "in the database". */
    val document: ExportDocument get() = stored.value

    val backupRepository: BackupRepository = object : BackupRepository {

        override suspend fun rowCounts(): Map<BackupTable, Long> =
            BackupTable.entries.associateWith { stored.value.rows(it).size.toLong() }

        override suspend fun readPage(
            table: BackupTable,
            offset: Int,
            limit: Int,
        ): List<BackupRow> = stored.value.rows(table).drop(offset).take(limit)

        override suspend fun <T> readConsistently(block: suspend () -> T): T = block()

        override suspend fun replaceAll(
            document: ExportDocument,
            onTableWritten: suspend (BackupTable, Long) -> Unit,
        ) {
            var written = 0L
            for (table in BackupTable.entries) {
                written += document.rows(table).size
                onTableWritten(table, written)
            }
            stored.value = document
        }
    }

    /**
     * The footprint, derived from what is stored rather than held beside it.
     *
     * Derived so a restore *moves* it: a test that wiped the store and then asserted on a
     * footprint frozen at construction would be asserting on a constant.
     */
    val localDataRepository: LocalDataRepository = object : LocalDataRepository {

        override fun observeFootprint(): Flow<DataFootprint> =
            stored.map { document ->
                DataFootprint(
                    sessions = document.workoutSession.size,
                    sets = document.workoutSet.size,
                    firstSessionAt = document.workoutSession.minOfOrNull { it.createdAt },
                    databaseBytes = document.rowCount * BYTES_PER_ROW,
                )
            }

        override suspend fun deleteAll() {
            stored.value = EMPTY_DOCUMENT
        }
    }

    /** Puts one finished session in the store, so an export has something to carry. */
    fun seedOneSession() {
        stored.update { document ->
            document.copy(
                workoutSession = listOf(
                    WorkoutSessionRow(
                        id = 1,
                        name = "Push Day",
                        completedAt = SESSION_CREATED_AT + HOUR_MILLIS,
                        isInProgress = false,
                        lastActiveAt = SESSION_CREATED_AT + HOUR_MILLIS,
                        createdAt = SESSION_CREATED_AT,
                        updatedAt = SESSION_CREATED_AT + HOUR_MILLIS,
                    ),
                ),
            )
        }
    }

    /** Empties the store, standing in for the device an exported file is restored onto. */
    fun wipe() {
        stored.value = EMPTY_DOCUMENT
    }

    fun sessionCount(): Int = stored.value.workoutSession.size

    companion object {

        /** 2024-04-01, so "training since April 2024" has a month to name. */
        const val SESSION_CREATED_AT = 1_711_929_600_000L

        private const val HOUR_MILLIS = 3_600_000L

        /** An invented figure, so the footprint's size is non-zero and moves with the rows. */
        private const val BYTES_PER_ROW = 256L

        private val EMPTY_DOCUMENT = ExportDocument(
            exportedAt = 0,
            appVersion = "",
            preferences = UserPreferences().toPayload(),
        )
    }
}

/** The settings store, in memory, honouring the read-whole/write-one-at-a-time contract. */
class InMemoryUserPreferences : UserPreferencesRepository {

    private val preferences = MutableStateFlow(UserPreferences())

    override fun observe(): Flow<UserPreferences> = preferences.asStateFlow()

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

    override suspend fun setAutoOpenRestTimer(enabled: Boolean) = write { it.copy(autoOpenRestTimer = enabled) }

    override suspend fun setWeeklyWorkoutGoal(goal: Int) = write { it.copy(weeklyWorkoutGoal = goal) }

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

    /** What the store holds, for a test asserting a restore put the file's settings back. */
    val current: UserPreferences get() = preferences.value

    /** What an export of this store would carry. */
    fun payload(): PreferencesPayload = preferences.value.toPayload()

    private fun write(transform: (UserPreferences) -> UserPreferences) = preferences.update(transform)
}

/**
 * The auto-backup store, in memory, with a switch for the write failure the screen has to
 * explain — an unwritable settings file is not something a temporary folder can be made to
 * produce, and it is the one path where the toggle has to say something rather than move.
 */
class InMemoryAutoBackupSchedule : AutoBackupRepository {

    private val schedule = MutableStateFlow(AutoBackupSchedule())

    /** When true, every write throws the way a full or read-only store does. */
    var writesFail = false

    override fun observe(): Flow<AutoBackupSchedule> = schedule.asStateFlow()

    override suspend fun read(): AutoBackupSchedule = schedule.value

    override suspend fun setEnabled(enabled: Boolean) = write { it.copy(enabled = enabled) }

    override suspend fun recordSuccess(at: Long) = write { it.copy(lastSuccessAt = at, lastFailureAt = null) }

    override suspend fun recordFailure(at: Long) = write { it.copy(lastFailureAt = at) }

    /** What the store holds, for a test asserting on it without collecting the flow. */
    val current: AutoBackupSchedule get() = schedule.value

    private fun write(transform: (AutoBackupSchedule) -> AutoBackupSchedule) {
        if (writesFail) throw IOException("the auto-backup store is not writable")
        schedule.update(transform)
    }
}

/**
 * The platform half of the toggle, counted rather than performed.
 *
 * The count is a [MutableStateFlow] rather than a plain `var`: [schedule] and [cancel] run on
 * [kotlinx.coroutines.Dispatchers.IO], off any dispatcher a test controls, so a call landing
 * after the assertion that expects it is a real race, not a hypothetical one. [awaitSchedule]
 * and [awaitCancel] give a test something to suspend on instead of reading the count the
 * instant the switch's own state settles.
 */
class RecordingAutoBackupScheduler : AutoBackupScheduler {

    private val schedules = MutableStateFlow(0)
    private val cancels = MutableStateFlow(0)

    val scheduleCount: Int get() = schedules.value

    val cancelCount: Int get() = cancels.value

    override fun schedule() {
        schedules.update { it + 1 }
    }

    override fun cancel() {
        cancels.update { it + 1 }
    }

    /** Suspends until [schedule] has been called at least once. */
    suspend fun awaitSchedule() {
        schedules.first { it >= 1 }
    }

    /** Suspends until [cancel] has been called at least once. */
    suspend fun awaitCancel() {
        cancels.first { it >= 1 }
    }
}

/** The in-progress draft, counting the clears an import is supposed to perform. */
class RecordingDraftStore : InProgressSessionRepository {

    private var session: ActiveSession? = null

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

/** The half-built template draft, in memory, counting the clears an import performs. */
class RecordingTemplateDraftStore : TemplateDraftRepository {

    private var draft: TemplateDraft? = null

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

/** The live session state, in memory. */
class InMemorySessionState : SessionStateRepository {

    private val state = MutableStateFlow<SessionState>(SessionState.Idle)

    override fun observe(): Flow<SessionState> = state.asStateFlow()

    override suspend fun update(state: SessionState) {
        this.state.value = state
    }

    val current: SessionState get() = state.value
}

/** The reminder alarms, counting the re-syncs a restore asks for once the settings are in. */
class RecordingReminderScheduler : ReminderScheduler {
    var syncs = 0
        private set

    override suspend fun sync() {
        syncs++
    }

    override fun isExact(): Boolean = true
}
