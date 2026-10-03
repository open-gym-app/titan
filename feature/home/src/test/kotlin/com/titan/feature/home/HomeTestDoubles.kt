package com.titan.feature.home

import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseAttempt
import com.titan.core.domain.model.ExerciseMuscleGroupLink
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.MobilityExercise
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.domain.model.MuscleTraining
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PersonalRecordEntry
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.RecordTally
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.SessionExercise
import com.titan.core.domain.model.SessionExerciseDraft
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.model.SessionTotals
import com.titan.core.domain.model.StreakState
import com.titan.core.domain.model.StreakUpdate
import com.titan.core.domain.model.Tag
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateExercise
import com.titan.core.domain.model.TemplateExerciseDraft
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TrainedExercise
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UnlockedAchievement
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.model.UserProgress
import com.titan.core.domain.model.WeeklyTraining
import com.titan.core.domain.model.WeeklyVolume
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.domain.model.XpAward
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.PersonalRecordRepository
import com.titan.core.domain.repository.ProgressionRepository
import com.titan.core.domain.repository.QuestRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.repository.TemplateRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.stats.TrainingWeek
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.time.Duration

/*
 * The stores behind everything Home reads, in memory.
 *
 * Hand-written rather than mocked, for the reason the other screens' doubles give: what is
 * under test is the state that comes out — a banner assembled from three reads, and a week
 * counted off a history — and asserting on that is both shorter and stricter than stubbing
 * each read in turn. The discard path is the strictest case of all: it runs two real use cases
 * over these stores, so "the banner goes away" is the row actually being deleted rather than a
 * verified call.
 *
 * Methods Home never reaches are left unsupported rather than given a plausible body nobody
 * has checked.
 */

/** A clock whose reading only changes when a test says so. */
class FixedClock(var now: Long) : Clock {
    override fun nowMillis(): Long = now
}

/** The sessions, their sets, and which one is still open. */
class FakeSessionStore(sessions: List<WorkoutSession> = emptyList()) : SessionRepository {

    private val stored = MutableStateFlow(sessions.associateBy(WorkoutSession::id))
    private val sets = MutableStateFlow<Map<Long, List<WorkoutSet>>>(emptyMap())

    /** When set, deleting a session throws it — the workout that cannot be thrown away. */
    var deleteFailure: Throwable? = null

    override fun getAll(): Flow<List<WorkoutSession>> = stored.map { it.values.toList() }

    override fun getById(id: Long): Flow<WorkoutSession?> = stored.map { it[id] }

    override fun getActive(): Flow<WorkoutSession?> =
        stored.map { sessions ->
            sessions.values.filter(WorkoutSession::isInProgress).maxByOrNull(WorkoutSession::lastActiveAt)
        }

    override fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>> = sets.map { it[sessionId].orEmpty() }

    override suspend fun update(session: WorkoutSession) {
        stored.update { it + (session.id to session) }
    }

    override suspend fun delete(id: Long) {
        deleteFailure?.let { throw it }
        stored.update { it - id }
        sets.update { it - id }
    }

    override suspend fun <T> atomically(block: suspend () -> T): T = block()

    /** [count] logged sets against [sessionId] — what discarding it would cost. */
    fun seedSets(
        sessionId: Long,
        count: Int,
    ) {
        sets.update {
            it + (
                sessionId to (1..count).map { number ->
                    WorkoutSet(
                        id = number.toLong(),
                        sessionExerciseId = sessionId,
                        setNumber = number,
                        reps = 8,
                        weight = 60_000_000L,
                        isCompleted = true,
                        completedAt = 1L,
                        createdAt = 1L,
                        updatedAt = 1L,
                    )
                }
                )
        }
    }

    fun setSessions(sessions: List<WorkoutSession>) {
        stored.value = sessions.associateBy(WorkoutSession::id)
    }

    /** Direct reads, bypassing the flows, for assertions. */
    fun storedSessions(): List<WorkoutSession> = stored.value.values.toList()

    override fun getExercises(sessionId: Long): Flow<List<SessionExercise>> = unsupported()
    override fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>> = unsupported()
    override fun getPreviousSets(sessionId: Long): Flow<Map<Long, List<WorkoutSet>>> = unsupported()
    override fun getTotals(): Flow<Map<Long, SessionTotals>> = unsupported()

    override suspend fun countFinished(): Int = unsupported()
    override suspend fun insert(session: WorkoutSession): Long = unsupported()
    override suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long> = unsupported()

    override suspend fun insertSet(set: WorkoutSet): Long = unsupported()
    override suspend fun insertSets(sets: List<WorkoutSet>): List<Long> = unsupported()
    override suspend fun updateSet(set: WorkoutSet) = unsupported()
    override suspend fun updateSets(sets: List<WorkoutSet>) = unsupported()
    override suspend fun deleteSet(setId: Long) = unsupported()
    override suspend fun updateExercise(exercise: SessionExercise) = unsupported()
    override suspend fun updateExercises(exercises: List<SessionExercise>) = unsupported()
    override suspend fun deleteExercise(sessionExerciseId: Long) = unsupported()

    /** Home reads sessions and deletes one. Anything else is a change of behaviour. */
    private fun unsupported(): Nothing = throw UnsupportedOperationException("home reads sessions and discards one")
}

/** The in-progress draft: one slot, which is what the contract says it is. */
class FakeDraftStore(initial: ActiveSession? = null) : InProgressSessionRepository {

    private var draft: ActiveSession? = initial

    var clearCount = 0
        private set

    override suspend fun save(session: ActiveSession) {
        draft = session
    }

    override suspend fun load(): ActiveSession? = draft

    override suspend fun clear() {
        clearCount++
        draft = null
    }
}

/** The session state machine. In memory, exactly as the real one is. */
class FakeSessionStateStore(initial: SessionState = SessionState.Idle) : SessionStateRepository {

    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<SessionState> = state.asStateFlow()

    override suspend fun update(state: SessionState) {
        this.state.value = state
    }

    val current: SessionState get() = state.value
}

/**
 * The catalogue, read for the one name the resume banner puts in its position line and for the
 * exercise types the template estimate reads.
 */
class FakeExerciseCatalogue(private val exercises: List<Exercise> = emptyList()) : ExerciseRepository {

    override fun getById(id: Long): Flow<Exercise?> = MutableStateFlow(exercises.find { it.id == id })

    override suspend fun getByIds(ids: Collection<Long>): List<Exercise> = exercises.filter { it.id in ids }

    override fun getAll(): Flow<List<Exercise>> = MutableStateFlow(exercises)

    override fun getAllMuscleGroupLinks(): Flow<Map<Long, List<ExerciseMuscleGroupLink>>> = unsupported()
    override fun getByType(type: ExerciseType): Flow<List<Exercise>> = unsupported()
    override fun getByEquipmentType(equipmentType: EquipmentType): Flow<List<Exercise>> = unsupported()
    override fun searchByPartialName(query: String): Flow<List<Exercise>> = unsupported()
    override fun getMuscleGroupLinks(exerciseId: Long): Flow<List<ExerciseMuscleGroupLink>> = unsupported()
    override fun getTags(exerciseId: Long): Flow<List<Tag>> = unsupported()

    override fun getMobilityExercises(): Flow<List<MobilityExercise>> = unsupported()
    override fun getMuscleGroups(): Flow<List<MuscleGroup>> = unsupported()
    override suspend fun insert(exercise: Exercise): Long = unsupported()
    override suspend fun insert(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ): Long = unsupported()

    override suspend fun update(exercise: Exercise) = unsupported()
    override suspend fun update(
        exercise: Exercise,
        muscleGroups: List<ExerciseMuscleGroupLink>,
    ) = unsupported()

    override suspend fun delete(id: Long) = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("home reads one exercise by id")
}

/** The saved templates. Only the two reads `GetTemplateSummariesUseCase` makes are real. */
class FakeTemplateStore(
    templates: List<WorkoutTemplate> = emptyList(),
    exercises: List<TemplateExercise> = emptyList(),
) : TemplateRepository {

    private val stored = MutableStateFlow(templates)
    private val body = MutableStateFlow(exercises)

    override fun getAll(): Flow<List<WorkoutTemplate>> = stored.map { it.sortedBy(WorkoutTemplate::name) }

    override fun getAllExercises(): Flow<List<TemplateExercise>> = body

    override fun getById(id: Long): Flow<WorkoutTemplate?> = unsupported()
    override fun getExercises(templateId: Long): Flow<List<TemplateExercise>> = unsupported()
    override suspend fun insert(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ): Long = unsupported()

    override suspend fun update(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ) = unsupported()

    override suspend fun delete(id: Long) = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("home only lists templates")
}

/** The settings store, for the one field the template estimate is priced against. */
class FakeSettings(initial: UserPreferences = UserPreferences()) : UserPreferencesRepository {

    private val preferences = MutableStateFlow(initial)

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

/** The one progress row: the level, the XP and the streak, as a screen reads them. */
class FakeProgressStore(progress: UserProgress? = null) : GamificationRepository {

    private val stored = MutableStateFlow(progress)

    override fun observeProgress(): Flow<UserProgress?> = stored.asStateFlow()

    fun setProgress(progress: UserProgress?) {
        stored.value = progress
    }

    override suspend fun addXp(
        delta: Int,
        levelForTotalXp: (totalXp: Long) -> Int,
    ): XpAward = unsupported()

    override suspend fun unlockedAchievementIds(): Set<String> = unsupported()
    override fun observeUnlockedAchievements(): Flow<List<UnlockedAchievement>> = unsupported()
    override suspend fun unlockAchievement(
        achievementId: String,
        unlockedAt: Instant,
    ): Boolean = unsupported()

    override suspend fun claimSessionXp(sessionId: Long): Boolean = unsupported()
    override suspend fun claimSessionLoot(sessionId: Long): Boolean = unsupported()
    override suspend fun updateStreak(advance: (state: StreakState) -> StreakUpdate): StreakUpdate = unsupported()

    /**
     * Home draws its streak off [observeProgress], never off this — the row carries the count
     * the screen needs, and a second flow would be a second thing to keep in step with it.
     */
    override fun observeStreak(): Flow<StreakState> = unsupported()

    /** Home reads the standing. Writing to it is the cascade's, and never this screen's. */
    private fun unsupported(): Nothing = throw UnsupportedOperationException("home only reads progress")
}

/**
 * The records table, as far as `LatestPersonalRecordUseCase` reads it: a count to watch, and a
 * page to take the newest off.
 *
 * The page is served from the same list the count is taken of, so a test that seeds two records
 * cannot end up with a count and a row that disagree — which is the one thing the use case's
 * two reads could get wrong.
 */
class FakeRecordStore(records: List<PersonalRecordEntry> = emptyList()) : PersonalRecordRepository {

    private val stored = MutableStateFlow(records)

    override fun observeRecordCount(): Flow<Int> = stored.map { it.size }

    override suspend fun getRecordsPage(
        type: PrType?,
        offset: Int,
        limit: Int,
    ): List<PersonalRecordEntry> =
        stored.value
            .filter { type == null || it.record.type == type }
            .drop(offset)
            .take(limit)

    fun setRecords(records: List<PersonalRecordEntry>) {
        stored.value = records
    }

    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> = unsupported()
    override fun getRecordsForExercise(exerciseId: Long): Flow<List<PersonalRecord>> = unsupported()
    override fun getRecordsForSession(sessionId: Long): Flow<List<PersonalRecord>> = unsupported()
    override fun observeSessionsWithRecords(): Flow<Set<Long>> = unsupported()
    override fun getRecordTally(days: ClosedRange<LocalDate>): Flow<RecordTally> = unsupported()
    override suspend fun count(): Int = unsupported()
    override suspend fun insertIfBetter(
        exerciseId: Long,
        type: PrType,
        record: PersonalRecord,
        isBetter: (current: PersonalRecord?) -> Boolean,
    ): PersonalRecord? = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("home reads the newest record")
}

/**
 * The weekly aggregates, as far as `VolumeTrendUseCase` reads them.
 *
 * [setWeeks] takes whole weeks and the read filters them by the period it is asked for, exactly
 * as the query does — which is what lets a test seed nine weeks and assert that the card draws
 * eight.
 */
class FakeVolumeStore(weeks: List<WeeklyVolume> = emptyList()) : ProgressionRepository {

    private val stored = MutableStateFlow(weeks)

    override fun getWeeklyVolume(days: ClosedRange<LocalDate>): Flow<List<WeeklyVolume>> =
        stored.map { rows -> rows.filter { it.weekStart in days }.sortedBy(WeeklyVolume::weekStart) }

    override fun hasTrainingHistory(): Flow<Boolean> = stored.map { it.isNotEmpty() }

    /**
     * [volumes] in milligram-reps, the most recent week last, ending on [lastWeekOf]'s week.
     *
     * A zero is stored as **no row**, which is what the query does: the aggregate is a `GROUP
     * BY` over sets that exist, and a week nobody trained in has none. Filling those gaps back
     * in is `VolumeTrendUseCase`'s job, so a fake that stored the zeroes would be doing the
     * work under test.
     */
    fun setWeeks(
        lastWeekOf: LocalDate,
        volumes: List<Long>,
    ) {
        val lastWeek = TrainingWeek.startOf(lastWeekOf)
        stored.value = volumes
            .mapIndexed { index, volume ->
                WeeklyVolume(
                    weekStart = lastWeek.minusWeeks((volumes.size - 1 - index).toLong()),
                    totalVolume = volume,
                )
            }
            .filter { it.totalVolume > 0 }
    }

    override fun getWeeklyTraining(days: ClosedRange<LocalDate>): Flow<List<WeeklyTraining>> = unsupported()
    override fun getMuscleTraining(days: ClosedRange<LocalDate>): Flow<List<MuscleTraining>> = unsupported()
    override fun getTrainingMillis(days: ClosedRange<LocalDate>): Flow<Long> = unsupported()
    override fun countSessionsFinished(days: ClosedRange<LocalDate>): Flow<Int> = unsupported()
    override fun getVolume(days: ClosedRange<LocalDate>): Flow<Long> = unsupported()
    override fun getFirstTrainingDay(): Flow<LocalDate?> = unsupported()
    override fun getExerciseAttempts(
        exerciseId: Long,
        days: ClosedRange<LocalDate>,
    ): Flow<List<ExerciseAttempt>> = unsupported()

    override fun countExerciseSets(exerciseId: Long): Flow<Int> = unsupported()
    override fun getTrainedExercises(): Flow<List<TrainedExercise>> = unsupported()
    override fun getRecentPerformances(
        exerciseId: Long,
        excludingSessionId: Long?,
        limit: Int,
    ): Flow<List<PastPerformance>> = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("home reads volume by week")
}

/** A saved template, with [exerciseCount] rows under it so its estimate is not zero. */
fun template(
    id: Long,
    name: String,
    exerciseCount: Int = 1,
    category: TemplateCategory? = null,
): Pair<WorkoutTemplate, List<TemplateExercise>> =
    WorkoutTemplate(id = id, name = name, category = category) to List(exerciseCount) { position ->
        TemplateExercise(
            id = id * 100 + position,
            templateId = id,
            exerciseId = position + 1L,
            position = position,
            sets = List(3) { PlannedSet() },
        )
    }

/*
 * The three reads the quest board is built from.
 *
 * Only the aggregates `QuestProgressUseCase` actually calls are implemented; every other member
 * throws, on the same terms as the stores above — a stub that quietly returned a plausible value
 * would let a screen that started reading one pass this suite unnoticed.
 *
 * Home's own suite runs with an **empty catalogue** by default, so none of these is touched and
 * the card is absent. The tests that are about the card pass a catalogue in — see
 * [HomeViewModelTest].
 */

/** The session and volume aggregates, as flows a test writes to. */
class FakeQuestAggregates : ProgressionRepository {

    val sessionsFinished = MutableStateFlow(0)
    val volumeMilligrams = MutableStateFlow(0L)

    override fun countSessionsFinished(days: ClosedRange<LocalDate>): Flow<Int> = sessionsFinished

    override fun getVolume(days: ClosedRange<LocalDate>): Flow<Long> = volumeMilligrams

    override fun getWeeklyVolume(days: ClosedRange<LocalDate>): Flow<List<WeeklyVolume>> = unused()

    override fun getWeeklyTraining(days: ClosedRange<LocalDate>): Flow<List<WeeklyTraining>> = unused()

    override fun hasTrainingHistory(): Flow<Boolean> = unused()

    override fun getMuscleTraining(days: ClosedRange<LocalDate>): Flow<List<MuscleTraining>> = unused()

    override fun getTrainingMillis(days: ClosedRange<LocalDate>): Flow<Long> = unused()

    override fun getFirstTrainingDay(): Flow<LocalDate?> = unused()

    override fun getExerciseAttempts(
        exerciseId: Long,
        days: ClosedRange<LocalDate>,
    ): Flow<List<ExerciseAttempt>> = unused()

    override fun countExerciseSets(exerciseId: Long): Flow<Int> = unused()

    override fun getTrainedExercises(): Flow<List<TrainedExercise>> = unused()

    override fun getRecentPerformances(
        exerciseId: Long,
        excludingSessionId: Long?,
        limit: Int,
    ): Flow<List<PastPerformance>> = unused()

    private fun unused(): Nothing = error("the quest board does not read this")
}

/** The record tally, as a flow a test writes to. */
class FakeQuestRecords : PersonalRecordRepository {

    val tally = MutableStateFlow(RecordTally(records = 0, exercises = 0))

    override fun getRecordTally(days: ClosedRange<LocalDate>): Flow<RecordTally> = tally

    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> = unused()

    override fun getRecordsForExercise(exerciseId: Long): Flow<List<PersonalRecord>> = unused()

    override fun getRecordsForSession(sessionId: Long): Flow<List<PersonalRecord>> = unused()

    override fun observeSessionsWithRecords(): Flow<Set<Long>> = unused()

    override suspend fun count(): Int = unused()

    override suspend fun getRecordsPage(
        type: PrType?,
        offset: Int,
        limit: Int,
    ): List<PersonalRecordEntry> = unused()

    override fun observeRecordCount(): Flow<Int> = unused()

    override suspend fun insertIfBetter(
        exerciseId: Long,
        type: PrType,
        record: PersonalRecord,
        isBetter: (current: PersonalRecord?) -> Boolean,
    ): PersonalRecord? = unused()

    private fun unused(): Nothing = error("the quest board does not read this")
}

/** What has been banked this period, as a flow a test writes to. */
class FakeQuestCompletions : QuestRepository {

    val completed = MutableStateFlow(emptySet<String>())

    override fun observeCompletedIds(periodStart: LocalDate): Flow<Set<String>> = completed

    override suspend fun completeQuest(
        questId: String,
        periodStart: LocalDate,
        completedAt: Instant,
    ): Boolean = error("the quest board never writes")
}
