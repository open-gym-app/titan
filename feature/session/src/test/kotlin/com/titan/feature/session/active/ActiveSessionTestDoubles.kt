package com.titan.feature.session.active

import com.titan.core.domain.model.ActiveSession
import com.titan.core.domain.model.Discipline
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseAttempt
import com.titan.core.domain.model.ExerciseMuscleGroupLink
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.MobilityExercise
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.domain.model.MuscleTraining
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.SessionExercise
import com.titan.core.domain.model.SessionExerciseDraft
import com.titan.core.domain.model.SessionTotals
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.TrainedExercise
import com.titan.core.domain.model.WeeklyTraining
import com.titan.core.domain.model.WeeklyVolume
import com.titan.core.domain.model.WorkoutSession
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.notification.NotificationPermission
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.ProgressionRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.session.IdleSessionMonitor
import com.titan.core.domain.time.Clock
import com.titan.core.domain.timer.BackgroundRest
import com.titan.core.domain.timer.BackgroundRestTimer
import com.titan.core.domain.timer.RestSkipRequests
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * The stores the active session reads and writes, in memory.
 *
 * Hand-written rather than mocked for the reason the domain's own doubles give: this screen is
 * a sequence of writes over one session, and asserting on what the store ends up holding is
 * both shorter and stricter than stubbing each read in turn. It also means the set the screen
 * logs comes back out of the *read* flow, which is what the "stats update immediately" criterion
 * actually asks for — a stubbed read would pass while the two disagreed.
 *
 * @param others rows that are not the session being logged — a workout finished last week — for
 *   the tests of a page opened on one of them. Read by id and never written.
 */
class FakeActiveSessionStore(
    session: WorkoutSession? = null,
    exercises: List<SessionExercise> = emptyList(),
    sets: List<WorkoutSet> = emptyList(),
    previousSets: Map<Long, List<WorkoutSet>> = emptyMap(),
    private val others: List<WorkoutSession> = emptyList(),
) : SessionRepository {

    private val session = MutableStateFlow(session)
    private val exercises = MutableStateFlow(exercises)
    private val sets = MutableStateFlow(sets)
    private val previousSets = MutableStateFlow(previousSets)
    private var nextSetId = (sets.maxOfOrNull(WorkoutSet::id) ?: 0L) + 1
    private var nextExerciseId = (exercises.maxOfOrNull(SessionExercise::id) ?: 0L) + 1

    /** Every set written through [insertSet], oldest first. */
    val logged = mutableListOf<WorkoutSet>()

    /**
     * The `workout_set` rows as they now stand.
     *
     * The screen reads a set as the row it is drawn in — a set number is its place in the table,
     * and the weight is text in the user's unit — so the stored numbering is a thing only the
     * store can be asked about. That is what a renumbering after a deletion is asserted against.
     */
    val stored: List<WorkoutSet> get() = sets.value

    /** Thrown by [insertSet] instead of storing, when set. */
    var insertFailure: Throwable? = null

    /**
     * Runs inside [insertSet], before the row is stored, with the set being written.
     *
     * Lets a test hold one write open while another starts — which is not a contrivance on this
     * screen but the ordinary way a superset is logged: its members are alternated set for set,
     * so two ticks land within a second of each other and both writes are genuinely in flight.
     */
    var beforeInsert: (suspend (WorkoutSet) -> Unit)? = null

    /**
     * Thrown by [update] instead of storing, when set.
     *
     * The session row is what every lifecycle transition writes, so this is how a test says
     * "the disk is full" to a pause, a resume or a finish. It throws rather than returning a
     * refusal because that is what Room does, and the difference is the whole point of the
     * tests that use it.
     */
    var updateFailure: Throwable? = null

    override fun getById(id: Long): Flow<WorkoutSession?> =
        session.map { it?.takeIf { s -> s.id == id } ?: others.firstOrNull { s -> s.id == id } }

    /** The session being logged as it now stands, or null once it has been deleted. */
    val current: WorkoutSession? get() = session.value

    override fun getExercises(sessionId: Long): Flow<List<SessionExercise>> =
        exercises.map { rows -> rows.filter { it.sessionId == sessionId } }

    override fun getSets(sessionExerciseId: Long): Flow<List<WorkoutSet>> =
        sets.map { rows -> rows.filter { it.sessionExerciseId == sessionExerciseId } }

    override fun getSetsForSession(sessionId: Long): Flow<List<WorkoutSet>> =
        combine(exercises, sets) { exerciseRows, setRows ->
            val ids = exerciseRows.filter { it.sessionId == sessionId }.mapTo(HashSet()) { it.id }
            setRows.filter { it.sessionExerciseId in ids }
        }

    /**
     * How many times the previous-performance read has been asked for.
     *
     * Held rather than derived because it is the assertion itself: the contract is one query
     * for the whole workout, and the failure it exists to catch — a lookup per exercise — is
     * invisible in the *values* and obvious in the count. Sessions are laid out with ten
     * exercises in the test that reads this.
     */
    var previousSetsReads = 0
        private set

    /**
     * Held rather than derived from what this fake stores, which is the one read here that
     * cannot honestly be: "last time" is a query over sessions this store does not hold — it
     * holds the one being logged. What a test supplies is therefore the answer, and the rules
     * behind it (warm-ups out, most recent slot, absence means never) are asserted where they
     * live, against a real database, in `PreviousPerformanceDaoTest`.
     */
    override fun getPreviousSets(sessionId: Long): Flow<Map<Long, List<WorkoutSet>>> {
        previousSetsReads++
        return previousSets
    }

    /** Rewrites what the exercises did last time, as a later read would come back with it. */
    fun setPreviousSets(previous: Map<Long, List<WorkoutSet>>) {
        previousSets.value = previous
    }

    override suspend fun insertSet(set: WorkoutSet): Long {
        beforeInsert?.invoke(set)
        insertFailure?.let { throw it }
        val stored = set.copy(id = nextSetId++)
        sets.value += stored
        logged += stored
        return stored.id
    }

    override suspend fun insertSets(sets: List<WorkoutSet>): List<Long> = sets.map { insertSet(it) }

    /** Every set rewritten through [updateSet] or [updateSets], oldest first. */
    val updated = mutableListOf<WorkoutSet>()

    /** Thrown by [updateSet], [updateSets] and [deleteSet] instead of writing, when set. */
    var editFailure: Throwable? = null

    /**
     * Runs inside [updateSet], before the row is written, with the set being written.
     *
     * Lets a test hold a correction open while the user reopens another row — the window in
     * which a write coming back could close an editor it is not about.
     */
    var beforeUpdate: (suspend (WorkoutSet) -> Unit)? = null

    override suspend fun updateSet(set: WorkoutSet) {
        beforeUpdate?.invoke(set)
        editFailure?.let { throw it }
        updated += set
        sets.value = sets.value.map { if (it.id == set.id) set else it }
    }

    /*
     * Writes several sets at once, and checks the invariant the schema does not.
     *
     * `workout_set` has no unique index on `(session_exercise_id, set_number)` — the numbers are
     * kept contiguous by the use cases — so a fake that simply applied the list would let a
     * renumbering that leaves two sets on one number pass here and be read as "set 2" twice on a
     * device.
     */

    /**
     * Thrown by [updateSets] alone, when set.
     *
     * The second write of a delete or a restore — the renumbering — which is the one that can
     * fail *after* the row has already gone or already come back. [editFailure] fails every
     * write and so only ever reaches the first one; this reaches the window between them.
     */
    var renumberFailure: Throwable? = null

    override suspend fun updateSets(sets: List<WorkoutSet>) {
        editFailure?.let { throw it }
        renumberFailure?.let { throw it }
        sets.forEach { updateSet(it) }

        sets.map(WorkoutSet::sessionExerciseId).distinct().forEach { sessionExerciseId ->
            val numbers = this.sets.value
                .filter { it.sessionExerciseId == sessionExerciseId }
                .map(WorkoutSet::setNumber)
            check(numbers.size == numbers.distinct().size) {
                "two sets of exercise $sessionExerciseId ended up on one number: $numbers"
            }
        }
    }

    /**
     * Runs inside [deleteSet], before the row goes, with the id being removed.
     *
     * Lets a test hold a deletion open across another write to the same table — a swipe and the
     * undo of an earlier swipe are seconds apart on this screen, and both renumber the exercise.
     */
    var beforeDelete: (suspend (Long) -> Unit)? = null

    override suspend fun deleteSet(setId: Long) {
        beforeDelete?.invoke(setId)
        editFailure?.let { throw it }
        sets.value = sets.value.filterNot { it.id == setId }
    }

    override suspend fun updateExercise(exercise: SessionExercise) {
        exercises.value = exercises.value.map { if (it.id == exercise.id) exercise else it }
    }

    /**
     * Writes several rows at once, refusing what the unique `(session_id, position)` index
     * would refuse.
     *
     * The check is why this is not a loop over [updateExercise]: the real store parks the rows
     * out of the way before writing them back — `SessionExerciseDao.rewriteLayout` — and a fake
     * that simply applied the list would let a reorder leaving two exercises on one position
     * pass here and abort on a device.
     */
    override suspend fun updateExercises(exercises: List<SessionExercise>) {
        val written = exercises.associateBy(SessionExercise::id)
        this.exercises.value = this.exercises.value.map { written[it.id] ?: it }

        val positions = this.exercises.value.map(SessionExercise::position)
        check(positions.size == positions.distinct().size) {
            "two exercises ended up on one position: $positions"
        }
    }

    override suspend fun deleteExercise(sessionExerciseId: Long) {
        exercises.value = exercises.value.filterNot { it.id == sessionExerciseId }
        sets.value = sets.value.filterNot { it.sessionExerciseId == sessionExerciseId }
    }

    override suspend fun update(session: WorkoutSession) {
        updateFailure?.let { throw it }
        this.session.value = session
    }

    override suspend fun delete(id: Long) {
        val orphaned = exercises.value.filter { it.sessionId == id }.mapTo(HashSet()) { it.id }
        session.value = session.value?.takeIf { it.id != id }
        exercises.value = exercises.value.filterNot { it.sessionId == id }
        sets.value = sets.value.filterNot { it.sessionExerciseId in orphaned }
    }

    /** Just the block — no rollback; see the domain `FakeSessionRepository` for why. */
    override suspend fun <T> atomically(block: suspend () -> T): T = block()

    override fun getAll(): Flow<List<WorkoutSession>> = session.map { listOfNotNull(it) + others }

    override fun getActive(): Flow<WorkoutSession?> = session.map { it?.takeIf(WorkoutSession::isInProgress) }

    override fun getTotals(): Flow<Map<Long, SessionTotals>> = unsupported()

    override suspend fun countFinishedBy(atMillis: Long): Int = unsupported()

    override suspend fun insert(session: WorkoutSession): Long = unsupported()

    /**
     * Appends past the highest position in use, matching `SessionExerciseDao.appendAll`.
     *
     * Past the *highest* rather than the count, because a removal leaves a gap and numbering
     * from the count would land on a row that is still there — the exact collision the real
     * unique index would abort on, and one a fake that counted would hide.
     */
    override suspend fun insertExercises(
        sessionId: Long,
        exercises: List<SessionExerciseDraft>,
        createdAt: Long,
    ): List<Long> {
        val start = (this.exercises.value.maxOfOrNull(SessionExercise::position) ?: -1) + 1
        val stored = exercises.mapIndexed { index, draft ->
            SessionExercise(
                id = nextExerciseId++,
                sessionId = sessionId,
                exerciseId = draft.exerciseId,
                position = start + index,
                supersetGroup = draft.supersetGroup,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }
        this.exercises.value += stored
        return stored.map(SessionExercise::id)
    }

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the active session does not lay a workout out")
}

/**
 * The in-progress draft, held as the object rather than as its encoded document.
 *
 * `:core:domain`'s own fake stores the JSON, so that every test using it exercises
 * `ActiveSessionFormat` for free. That is the right trade there and the wrong one here: what
 * these tests assert is *when* the screen saves and what it saved, and a round trip through a
 * serializer between the two would put a second thing in the failure message.
 */
class FakeDraftStore(initial: ActiveSession? = null) : InProgressSessionRepository {

    private var draft: ActiveSession? = initial

    /** Every draft written, oldest first, so a test can assert on when a save happened. */
    val saves = mutableListOf<ActiveSession>()

    var clearCount = 0
        private set

    /** Thrown by [save] instead of storing, when set. */
    var saveFailure: Throwable? = null

    override suspend fun save(session: ActiveSession) {
        saveFailure?.let { throw it }
        draft = session
        saves += session
    }

    /**
     * Held closed by [holdLoad] until [releaseLoad], so a test can act on the screen while the
     * draft read is still in flight. That window is real: the screen goes interactive on the
     * session store's first emission, which lands well before a disk read comes back.
     */
    private var loadGate: CompletableDeferred<Unit>? = null

    /** Makes the next [load] wait for [releaseLoad]. */
    fun holdLoad() {
        loadGate = CompletableDeferred()
    }

    /** Lets a [load] held by [holdLoad] finish. */
    fun releaseLoad() {
        loadGate?.complete(Unit)
    }

    override suspend fun load(): ActiveSession? {
        // The value as it was when the read was *issued*, not as it is when the read comes
        // back. That is what a held read models: a disk read in flight does not pick up a save
        // that lands behind it, and the point of holding one is what the screen does meanwhile.
        val issued = draft
        loadGate?.await()
        return issued
    }

    override suspend fun clear() {
        clearCount++
        draft = null
    }

    /** The stored draft, read directly for assertions. */
    val stored: ActiveSession? get() = draft
}

/** A clock whose reading only changes when a test says so. */
class FixedClock(var now: Long = STARTED_AT) : Clock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long): Long {
        now += millis
        return now
    }
}

/**
 * The history the next-load engine reads (#M7-16), in memory.
 *
 * Only [getRecentPerformances] answers, and it answers with what a test seeded, newest first —
 * the query's own rules (warm-ups out, the session being logged left out, the newest slots) are
 * asserted against a real database in `RecentPerformanceDaoTest`. What this fake is *for* is the
 * count: the contract is one read per exercise for the session's life, and a re-read on every
 * keystroke is invisible in the values and obvious in [reads].
 */
class FakeProgressionStore : ProgressionRepository {

    private val performances = MutableStateFlow<Map<Long, List<PastPerformance>>>(emptyMap())

    /** Every (exercise, excluded session) the history has been asked for, in call order. */
    val reads = mutableListOf<Pair<Long, Long?>>()

    /** Seeds one exercise's history, newest first, each performance as its working sets. */
    fun setPerformances(
        exerciseId: Long,
        vararg newestFirst: List<WorkoutSet>,
    ) {
        performances.value += exerciseId to newestFirst.map(::PastPerformance)
    }

    override fun getRecentPerformances(
        exerciseId: Long,
        excludingSessionId: Long?,
        limit: Int,
    ): Flow<List<PastPerformance>> {
        reads += exerciseId to excludingSessionId
        return performances.map { byExercise -> byExercise[exerciseId].orEmpty().take(limit) }
    }

    override fun getWeeklyVolume(days: ClosedRange<LocalDate>): Flow<List<WeeklyVolume>> = unsupported()
    override fun getWeeklyTraining(days: ClosedRange<LocalDate>): Flow<List<WeeklyTraining>> = unsupported()
    override fun hasTrainingHistory(): Flow<Boolean> = unsupported()
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

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("the workout reads recent performances only")
}

// ─── Fixtures ───────────────────────────────────────────────────────────────────────────

const val ACTIVE_SESSION_ID = 7L
const val STARTED_AT = 1_700_000_000_000L

val BENCH = Exercise(id = 1L, name = "Bench press")
val INCLINE = Exercise(id = 2L, name = "Incline dumbbell press")
val FLY = Exercise(id = 3L, name = "Cable fly")
val PUSHDOWN = Exercise(id = 4L, name = "Tricep pushdown")

/** The session row as it stands mid-workout: in progress, with no `completedAt`. */
fun runningSession(name: String? = "Push day · Upper") =
    WorkoutSession(
        id = ACTIVE_SESSION_ID,
        name = name,
        completedAt = null,
        isInProgress = true,
        lastActiveAt = STARTED_AT,
        createdAt = STARTED_AT,
        updatedAt = STARTED_AT,
    )

fun activeExercise(
    id: Long,
    exerciseId: Long,
    position: Int,
    supersetGroup: Int? = null,
) = SessionExercise(
    id = id,
    sessionId = ACTIVE_SESSION_ID,
    exerciseId = exerciseId,
    position = position,
    supersetGroup = supersetGroup,
    createdAt = STARTED_AT,
    updatedAt = STARTED_AT,
)

/**
 * One logged set as the screen draws it: the values already in the fields.
 *
 * The row a screen test works with, as against [loggedSet], which is the row a *store* holds. The
 * two are different shapes on purpose — every row of the table is live, so what reaches the screen
 * is text in the user's own unit rather than milligrams. See `LoggedSet`.
 */
fun loggedRow(
    id: Long,
    weight: String = "60",
    reps: String = "8",
    rpe: Int? = null,
    setType: SetType = SetType.NORMAL,
    isCompleted: Boolean = true,
) = LoggedSet(
    id = id,
    entry = SetEntry(weight = weight, reps = reps, rpe = rpe, setType = setType),
    isCompleted = isCompleted,
)

/**
 * One row of `workout_set`, completed unless a test says otherwise.
 *
 * @param isCompleted false is a set that is persisted but not performed — what #99's editing
 *   makes ordinary, and the one case where "how many sets are under this exercise" and "how many
 *   did the lifter do" are different numbers.
 */
fun loggedSet(
    id: Long,
    sessionExerciseId: Long,
    setNumber: Int,
    reps: Int? = 8,
    weight: Long? = 60_000_000L,
    isCompleted: Boolean = true,
) = WorkoutSet(
    id = id,
    sessionExerciseId = sessionExerciseId,
    setNumber = setNumber,
    reps = reps,
    weight = weight,
    isCompleted = isCompleted,
    completedAt = STARTED_AT.takeIf { isCompleted } ?: 0L,
    createdAt = STARTED_AT,
    updatedAt = STARTED_AT,
)

/**
 * The rest as the operating system would keep it, recorded rather than posted.
 *
 * Every publication in order, because what these tests assert is *when* the screen tells the OS
 * about a rest and what it told it — a fake that only kept the latest could not tell a rest that
 * was published once from one republished on every tick, which is the property the chronometer
 * design depends on.
 */
class FakeBackgroundRestTimer : BackgroundRestTimer {

    /** Every call, oldest first. Null is "there is no rest". */
    val published = mutableListOf<BackgroundRest?>()

    /** The last thing published: a rest, or null for "there is no rest" and for nothing yet. */
    val current: BackgroundRest? get() = published.lastOrNull()

    override suspend fun set(rest: BackgroundRest?) {
        published += rest
    }
}

/**
 * "Skip rest" presses, driven by the test rather than by a notification.
 *
 * The real one is a `MutableSharedFlow` with no buffer, and this is the same thing: a press
 * emitted with nobody collecting is dropped, which is the behaviour the ViewModel's subscription
 * has to be tested against.
 */
class FakeRestSkipRequests : RestSkipRequests {

    private val requests = MutableSharedFlow<Long>()

    override val skipped: Flow<Long> = requests

    override suspend fun skip(sessionId: Long) {
        requests.emit(sessionId)
    }
}

/**
 * The idle nudge, as a list of what it was told.
 *
 * Ordered rather than a pair of counters, because what these tests assert is the *sequence*: an
 * arm that lands after a disarm is an alarm pending over a workout that is over, and two
 * counters cannot tell that from the right way round.
 */
class FakeIdleSessionMonitor : IdleSessionMonitor {

    /** Every call, oldest first. */
    val calls = mutableListOf<Call>()

    override suspend fun arm() {
        calls += Call.ARMED
    }

    override suspend fun disarm() {
        calls += Call.DISARMED
    }

    /** Whether the nudge is pointed at anything, as far as this fake is concerned. */
    val isArmed: Boolean get() = calls.lastOrNull() == Call.ARMED

    enum class Call { ARMED, DISARMED }
}

// ─── The warm-up and the cool-down (#214) ───────────────────────────────────────────────

val CHEST = MuscleGroup(id = 1L, name = "CHEST")
val SHOULDERS = MuscleGroup(id = 2L, name = "SHOULDERS")
val TRICEPS = MuscleGroup(id = 3L, name = "TRICEPS")

/** One exercise's muscle links: a prime mover, and whatever it assists with. */
fun links(
    primary: MuscleGroup,
    secondary: List<MuscleGroup> = emptyList(),
): List<ExerciseMuscleGroupLink> =
    listOf(ExerciseMuscleGroupLink(muscleGroup = primary, isPrimary = true)) +
        secondary.map { ExerciseMuscleGroupLink(muscleGroup = it, isPrimary = false) }

/**
 * A mobility catalogue with two of everything, so the planner has a choice to make.
 *
 * `:core:domain` has a fixture of the same shape for the planner's own tests. It is not shared,
 * because a test source set is not published: what is being checked here is that the *screen*
 * draws whatever the planner picked, not which rows those were.
 */
val MOBILITY_CATALOGUE: List<MobilityExercise> = buildList {
    var id = 100L
    listOf(CHEST, SHOULDERS, TRICEPS).forEach { muscle ->
        val name = muscle.name.lowercase()
        add(mobility(id++, "$name swing", muscle, isDynamic = true))
        add(mobility(id++, "$name circle", muscle, isDynamic = true))
        add(mobility(id++, "$name stretch", muscle, isDynamic = false))
        add(mobility(id++, "$name hold", muscle, isDynamic = false))
    }
}

private fun mobility(
    id: Long,
    name: String,
    primary: MuscleGroup,
    isDynamic: Boolean,
) = MobilityExercise(
    exercise = Exercise(
        id = id,
        name = name,
        type = ExerciseType.ISOMETRIC,
        discipline = Discipline.STRETCHING,
    ),
    primaryMuscleGroupIds = setOf(primary.id),
    isDynamic = isDynamic,
)

/**
 * Whether the system would carry a notification, as a switch the test flips.
 *
 * Allowed by default, so a test about anything else never sees the prompt go up over its first
 * rest. A `var` because the point of the prompt is that the answer changes while the screen is
 * on it.
 */
class FakeNotificationPermission(
    var allowed: Boolean = true,
    override val isRuntimePermissionRequired: Boolean = true,
) : NotificationPermission {

    override fun areNotificationsAllowed(): Boolean = allowed
}
