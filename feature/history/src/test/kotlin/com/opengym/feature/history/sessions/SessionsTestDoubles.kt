package com.opengym.feature.history.sessions

import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.SessionExerciseDraft
import com.opengym.core.domain.model.SessionTotals
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutSession
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.TemplateRepository
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/*
 * Hand-written fakes rather than mocks, for the reason the domain's own doubles give: these
 * tests exercise a screen over several reads and several filter changes, and asserting on the
 * state that comes out is both shorter and stricter than stubbing each read in turn.
 */

/** A clock whose reading only changes when a test says so. */
class FixedClock(var now: Long) : Clock {
    override fun nowMillis(): Long = now
}

/**
 * The sessions and their aggregates, in memory.
 *
 * @param sessions what the store holds, or **null** for a store that has not answered yet.
 *   Null is not "empty": a `StateFlow` seeded with an empty list emits one immediately, which
 *   is a settled answer, and the screen draws the two differently. The distinction is what
 *   lets a test pin the loading state at all.
 */
class FakeSessionStore(sessions: List<WorkoutSession>? = emptyList()) : SessionRepository {

    private val stored = MutableStateFlow(sessions)
    private val totals = MutableStateFlow<Map<Long, SessionTotals>>(emptyMap())

    override fun getAll(): Flow<List<WorkoutSession>> = stored.filterNotNull()

    override fun getTotals(): Flow<Map<Long, SessionTotals>> = totals

    /** Records what a session logged, as the grouped query would report it. */
    fun setTotals(sessionId: Long, sets: Int, volume: Long) {
        totals.value += sessionId to SessionTotals(totalSets = sets, totalVolume = volume)
    }

    fun setSessions(sessions: List<WorkoutSession>) {
        stored.value = sessions
    }

    override fun getById(id: Long): Flow<WorkoutSession?> = unsupported()
    override fun getActive(): Flow<WorkoutSession?> = unsupported()
    override fun getExercises(sessionId: Long): Flow<List<SessionExercise>> = unsupported()
    override fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>> = unsupported()
    override fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>> = unsupported()
    override suspend fun insert(session: WorkoutSession): Long = unsupported()
    override suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long> = unsupported()

    override suspend fun insertSet(set: WorkoutSet): Long = unsupported()
    override suspend fun updateExercise(exercise: SessionExercise) = unsupported()
    override suspend fun deleteExercise(sessionExerciseId: Long) = unsupported()
    override suspend fun update(session: WorkoutSession) = unsupported()
    override suspend fun delete(id: Long) = unsupported()

    /**
     * The sessions browser reads three things and writes none. Anything else reached from here
     * is a change of behaviour worth failing on rather than a gap worth stubbing.
     */
    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the sessions browser only reads")
}

/** The records the PR engine will one day write. Read-only, like the contract. */
class FakePersonalRecordStore(
    private val records: Map<Long, PersonalRecord> = emptyMap(),
) : PersonalRecordRepository {
    override fun getHeadlineRecords(): Flow<Map<Long, PersonalRecord>> = MutableStateFlow(records)
}

/** A record of the only kind that matters here: one achieved at a known instant. */
fun recordAt(exerciseId: Long, achievedAt: Long): PersonalRecord = PersonalRecord(
    id = exerciseId,
    exerciseId = exerciseId,
    workoutSetId = exerciseId,
    type = PrType.MAX_WEIGHT,
    weight = 100_000_000,
    achievedAt = achievedAt,
)

/** The saved templates. Only the two reads `GetTemplateSummariesUseCase` makes are real. */
class FakeTemplateStore(
    templates: List<WorkoutTemplate> = emptyList(),
    exercises: List<TemplateExercise> = emptyList(),
) : TemplateRepository {

    private val stored = MutableStateFlow(templates)
    private val body = MutableStateFlow(exercises)

    override fun getAll(): Flow<List<WorkoutTemplate>> = stored

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

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the templates tab only lists")
}

/** A template with a name and a category, and nothing else the row draws. */
fun namedTemplate(id: Long, name: String, category: TemplateCategory? = null): WorkoutTemplate =
    WorkoutTemplate(id = id, name = name, category = category)

// ─── Session fixtures ───────────────────────────────────────────────────────────────────

/**
 * The zone these fixtures are written in — the device's, which is the one the ViewModel reads
 * instants in.
 *
 * Deliberately *not* a fixed zone. The assertions below are about which calendar day a session
 * lands on, and the ViewModel answers that in the device's zone; a fixture pinned to UTC would
 * disagree with it by a day on any machine that is not, and the test would fail on the
 * behaviour being correct. Every instant is midday, so the two never disagree by a day here.
 */
val TEST_ZONE: ZoneId = ZoneId.systemDefault()

/** Midday on [date] in [TEST_ZONE], as epoch millis — far enough from either midnight. */
fun middayOn(date: LocalDate): Long =
    date.atTime(LocalTime.NOON).atZone(TEST_ZONE).toInstant().toEpochMilli()

/**
 * A finished session that started at midday on [date] and ran for [duration].
 *
 * `isInProgress` defaults to false because this is a *history* fixture: an unfinished session
 * is the exception here and a test that wants one says so.
 */
fun loggedSession(
    id: Long,
    date: LocalDate,
    name: String? = null,
    notes: String? = null,
    duration: Duration = 50.minutes,
    isInProgress: Boolean = false,
): WorkoutSession {
    val startedAt = middayOn(date)

    return WorkoutSession(
        id = id,
        name = name,
        notes = notes,
        isInProgress = isInProgress,
        completedAt = (startedAt + duration.inWholeMilliseconds).takeUnless { isInProgress },
        lastActiveAt = startedAt + duration.inWholeMilliseconds,
        createdAt = startedAt,
        updatedAt = startedAt,
    )
}
