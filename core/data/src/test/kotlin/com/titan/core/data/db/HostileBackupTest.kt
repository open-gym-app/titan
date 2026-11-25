package com.titan.core.data.db

import android.database.sqlite.SQLiteConstraintException
import com.titan.core.data.db.entity.TagEntity
import com.titan.core.data.db.entity.UserProgressEntity
import com.titan.core.data.mapper.ExerciseMapper
import com.titan.core.data.mapper.MuscleGroupLinkMapper
import com.titan.core.data.mapper.SessionExerciseMapper
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.data.mapper.TagMapper
import com.titan.core.data.mapper.WorkoutSetMapper
import com.titan.core.data.repository.BackupRepositoryImpl
import com.titan.core.data.repository.ExerciseRepositoryImpl
import com.titan.core.data.repository.GamificationRepositoryImpl
import com.titan.core.data.repository.ProgressionRepositoryImpl
import com.titan.core.data.repository.SessionRepositoryImpl
import com.titan.core.data.repository.SessionStateRepositoryImpl
import com.titan.core.domain.backup.ExportFormat
import com.titan.core.domain.backup.ExportSink
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.backup.model.ExportDocument
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.backup.model.ImportProgress
import com.titan.core.domain.backup.model.WorkoutSessionRow
import com.titan.core.domain.backup.model.WorkoutSetRow
import com.titan.core.domain.gamification.StreakCalculator
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.TemplateExercise
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.template.TemplateDuration
import com.titan.core.domain.timer.RestDurations
import com.titan.core.domain.usecase.ExportUseCase
import com.titan.core.domain.usecase.ImportUseCase
import com.titan.core.domain.usecase.NextLoadUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.days

/**
 * A backup somebody else wrote, restored, and then read by every screen it used to crash (#256).
 *
 * One test per row of the issue's table, and for each one both halves of the fix: the restore
 * boundary (the value is refused, or repaired on its way into the table) and the reader (a row
 * holding the value, written straight through the DAOs, still reads). Every test then checks
 * that the app can carry on — log a session, award XP, take a streak step — because a database
 * that reads but refuses every write is the same dead end.
 *
 * Over the real pipeline and a real (Robolectric) SQLite, because two of the failures were
 * SQLite's own: `SUM` raising `integer overflow`, and `AUTOINCREMENT` running out of ids.
 */
class HostileBackupTest : RoomTestBase() {

    private val backup by lazy { BackupRepositoryImpl(db, Dispatchers.Unconfined) }
    private val preferences = InMemoryUserPreferencesRepository()

    private val export by lazy {
        ExportUseCase(backupRepository = backup, userPreferencesRepository = preferences, clock = { EXPORTED_AT })
    }

    /** The draft store, kept so a refusal can be shown to have come before anything was cleared. */
    private val drafts = CountingInProgressSessionRepository()

    private val import by lazy {
        ImportUseCase(
            backupRepository = backup,
            userPreferencesRepository = preferences,
            inProgressSessionRepository = drafts,
            templateDraftRepository = CountingTemplateDraftRepository(),
            sessionStateRepository = SessionStateRepositoryImpl(),
            backgroundRestTimer = RecordingBackgroundRestTimer(),
            idleSessionMonitor = RecordingIdleSessionMonitor(),
            reminderScheduler = RecordingReminderScheduler(),
        )
    }

    private val gamification by lazy {
        GamificationRepositoryImpl(
            userProgressDao = db.userProgressDao(),
            achievementDao = db.achievementDao(),
            sessionXpClaimDao = db.sessionXpClaimDao(),
            sessionLootClaimDao = db.sessionLootClaimDao(),
        )
    }

    private val exercises by lazy {
        ExerciseRepositoryImpl(
            exerciseDao = db.exerciseDao(),
            muscleGroupDao = db.muscleGroupDao(),
            exerciseMapper = ExerciseMapper(),
            tagMapper = TagMapper(),
            muscleGroupLinkMapper = MuscleGroupLinkMapper(),
        )
    }

    private val sessions by lazy {
        SessionRepositoryImpl(
            database = db,
            sessionMapper = SessionMapper(),
            sessionExerciseMapper = SessionExerciseMapper(),
            setMapper = WorkoutSetMapper(),
        )
    }

    private val nextLoad by lazy {
        NextLoadUseCase(
            progressionRepository = ProgressionRepositoryImpl(
                setDao = db.workoutSetDao(),
                sessionDao = db.workoutSessionDao(),
                setMapper = WorkoutSetMapper(),
            ),
            exerciseRepository = exercises,
            userPreferencesRepository = preferences,
        )
    }

    // ─── Weights and durations past SetValidator's limits: SUM raised `integer overflow` ────────

    @Test
    fun `restored sets too heavy to sum leave history, stats and the dashboard readable`() =
        runBlocking {
            val progress = restore { document -> document.copy(workoutSet = document.workoutSet.overflowing()) }

            assertComplete(progress)
            assertEverySumReads()
            assertANewSessionCanBeLogged()
        }

    @Test
    fun `stored sets too heavy to sum leave history, stats and the dashboard readable`() =
        runBlocking {
            db.seedEveryTable()
            db.workoutSetDao().update(
                workoutSet(sessionExerciseId = 1, setNumber = 1, completedAt = FINISHED_AT, reps = 1, weight = HUGE)
                    .copy(id = 1, duration = HUGE, distance = HUGE),
            )
            db.workoutSetDao().insert(
                workoutSet(sessionExerciseId = 1, setNumber = 2, completedAt = FINISHED_AT, reps = 1, weight = HUGE)
                    .copy(duration = HUGE, distance = HUGE),
            )
            // Two sessions an aeon long each: the total training time overflows as well.
            db.workoutSessionDao().insert(session(isInProgress = false, completedAt = HUGE))
            db.workoutSessionDao().insert(session(isInProgress = false, completedAt = HUGE))

            assertEverySumReads()
            assertEquals(Long.MAX_VALUE, db.workoutSessionDao().getTrainingMillis(0, Long.MAX_VALUE / DAY_MS).first())
            assertANewSessionCanBeLogged()
        }

    // ─── Reps or weight past SetValidator's limits: the next-load suggestion threw ──────────────

    @Test
    fun `a restored set of more reps than an Int can add one to gets no suggestion rather than a crash`() =
        runBlocking {
            val progress = restore { document ->
                document.copy(
                    workoutSet = document.workoutSet.map {
                        if (it.id == 1L) {
                            it.copy(
                                weight = null,
                                reps = Int.MAX_VALUE,
                            )
                        } else {
                            it
                        }
                    },
                )
            }

            assertComplete(progress)
            assertNull(nextLoad(exerciseId = 1, sessionId = null).first())
            assertANewSessionCanBeLogged()
        }

    @Test
    fun `a stored set heavier than a Long can step up from gets no suggestion rather than a crash`() =
        runBlocking {
            db.seedEveryTable()
            db.workoutSetDao().update(
                workoutSet(
                    sessionExerciseId = 1,
                    setNumber = 1,
                    completedAt = FINISHED_AT,
                    reps = 6,
                    weight = Long.MAX_VALUE,
                )
                    .copy(id = 1),
            )

            assertNull(nextLoad(exerciseId = 1, sessionId = null).first())
        }

    // ─── completed_at far before 1970: the ALL timeframe built a week per bucket until OOM ──────

    @Test
    fun `a restored session finished before 1970 is refused and changes nothing`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()

            val progress = restoreOver { document ->
                document.copy(workoutSession = document.workoutSession.map { it.copy(completedAt = ANCIENT) })
            }

            assertRefused(progress, "workout_session.completed_at $ANCIENT")
            assertEquals(before, db.dumpEverything())
            assertANewSessionCanBeLogged()
        }

    /** The stats screen clamps the period itself; what the database owes it is honest buckets. */
    @Test
    fun `a stored session finished before 1970 falls in no weekly bucket`() =
        runBlocking {
            db.seedEveryTable()
            db.workoutSessionDao().insert(session(isInProgress = false, completedAt = ANCIENT))
            val end = FINISHED_AT / DAY_MS + 1

            val weeks = db.workoutSessionDao().getTrainingByWeek(ANCIENT / DAY_MS - 7, end).first()
            val volume = db.workoutSetDao().getVolumeByWeek(ANCIENT / DAY_MS - 7, end).first()

            // Epoch day −3 was a Monday, so a week opens on a day `d` with `(d + 3) mod 7 = 0`.
            assertTrue(weeks.all { Math.floorMod(it.weekStartEpochDay + 3, 7L) == 0L })
            assertTrue(volume.all { Math.floorMod(it.weekStartEpochDay + 3, 7L) == 0L })
            // Only the fixture's own finished sessions are counted: the one logged and the one
            // imported from Health Connect, which is a day trained like any other.
            assertEquals(2, weeks.sumOf { it.sessionCount })
        }

    // ─── exercise.default_rest = Long.MAX_VALUE: TemplateDuration subtracted ∞ from ∞ ──────────

    @Test
    fun `a restored exercise rest of Long MAX_VALUE prices a template at the user's default`() =
        runBlocking {
            val progress = restore { document ->
                document.copy(
                    exercise = document.exercise.map { if (it.id == 1L) it.copy(defaultRest = Long.MAX_VALUE) else it },
                )
            }

            assertComplete(progress)
            assertTemplateEstimateUsesTheDefaultRest()
        }

    @Test
    fun `a stored exercise rest of Long MAX_VALUE prices a template at the user's default`() =
        runBlocking {
            db.backupDao().insertExercises(listOf(exercise().copy(id = 1, defaultRest = Long.MAX_VALUE)))

            assertTemplateEstimateUsesTheDefaultRest()
        }

    // ─── A huge default rest: the rest timer held ∞/∞ and its progress bar threw ───────────────

    @Test
    fun `a restored default rest past a day reads back as the default, and so does every rest it seeds`() =
        runBlocking {
            val progress = restore { document ->
                document.copy(
                    preferences = document.preferences.copy(defaultRestDurationSeconds = 4_000_000_000_000_000),
                    exercise = document.exercise.map { it.copy(defaultRest = 4_000_000_000_000_000) },
                )
            }

            assertComplete(progress)
            val settings = preferences.observe().first()
            assertEquals(UserPreferences.DEFAULT_REST_DURATION, settings.defaultRestDuration)
            val rest = RestDurations.of(exercises.getById(1).first(), settings.defaultRestDuration)
            assertEquals(UserPreferences.DEFAULT_REST_DURATION, rest)
            assertTrue(rest <= 1.days)
        }

    // ─── user_progress out of range: the XP header, quest log, streak step and awards threw ────

    @Test
    fun `a restored progress row nothing writes is repaired on its way in`() =
        runBlocking {
            val progress = restore { document ->
                document.copy(
                    userProgress = document.userProgress.map {
                        it.copy(
                            totalXp = -5,
                            currentLevel = 0,
                            protectionRemaining = -1,
                            protectionPeriod = "last week",
                        )
                    },
                )
            }

            assertComplete(progress)
            val row = db.userProgressDao().getOnce()!!
            assertEquals(0L, row.totalXp)
            assertEquals(XpCalculator.FIRST_LEVEL, row.currentLevel)
            assertEquals(0, row.protectionRemaining)
            assertNull(row.protectionPeriod)
            assertProgressReadsAndMoves()
        }

    @Test
    fun `a restored total past what the curve can price is held at the ceiling`() =
        runBlocking {
            val progress = restore { document ->
                document.copy(userProgress = document.userProgress.map { it.copy(totalXp = 6_000_000_000_000_000) })
            }

            assertComplete(progress)
            assertEquals(XpCalculator.MAX_TOTAL_XP, db.userProgressDao().getOnce()!!.totalXp)
            assertProgressReadsAndMoves()
        }

    @Test
    fun `a stored progress row nothing writes still reads, awards and steps`() =
        runBlocking {
            db.seedEveryTable()
            db.userProgressDao().upsert(
                UserProgressEntity(
                    totalXp = -5,
                    currentLevel = 0,
                    currentStreak = -3,
                    protectionRemaining = -1,
                    protectionPeriod = "last week",
                ),
            )

            assertProgressReadsAndMoves()
        }

    @Test
    fun `a stored total past what the curve can price still reads, awards and steps`() =
        runBlocking {
            db.userProgressDao().upsert(UserProgressEntity(totalXp = Long.MAX_VALUE, currentLevel = Int.MAX_VALUE))

            assertProgressReadsAndMoves()
        }

    // ─── Ids AUTOINCREMENT would not assign: SQLITE_FULL, and a draft that would not build ─────

    @Test
    fun `a restored id AUTOINCREMENT cannot continue from is refused, and logging goes on`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()

            val progress = restoreOver { document ->
                document.copy(workoutSession = document.workoutSession + sessionRow(id = Long.MAX_VALUE))
            }

            assertRefused(progress, "workout_session.id ${Long.MAX_VALUE}")
            assertEquals(before, db.dumpEverything())
            assertANewSessionCanBeLogged()
        }

    @Test
    fun `a restored in-progress session numbered below one is refused`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()

            val progress = restoreOver { document ->
                document.copy(workoutSession = document.workoutSession + sessionRow(id = -1, inProgress = true))
            }

            assertRefused(progress, "workout_session.id -1")
            assertEquals(before, db.dumpEverything())
        }

    /** The draft refuses a session id below one, so offering this row for resume threw at launch. */
    @Test
    fun `a stored in-progress session numbered below one is never offered for resume`() =
        runBlocking {
            db.seedEveryTable()
            db.backupDao().insertSessions(
                listOf(session(lastActiveAt = FINISHED_AT + 1, createdAt = FINISHED_AT).copy(id = -1)),
            )

            val active = sessions.getActive().first()

            assertNotEquals(-1L, active?.id)
            assertEquals(2L, active?.id)
            assertANewSessionCanBeLogged()
        }

    // ─── What every test above reads, and what it then tries to write ─────────────────────────

    // ─── #257: names SQLite stores as one, and a second session in progress ─────────────────────

    /**
     * The audit's claim, checked against SQLite itself: two tag names that are different strings
     * in Kotlin, each with an unpaired surrogate, are one name to the unique index once SQLite
     * has converted them to the UTF-8 it stores. This is what `BackupValidator` comparing them
     * as Kotlin strings would have let through.
     */
    @Test
    fun `two names with a lone surrogate can be one name to SQLite`() =
        runBlocking {
            db.tagDao().insert(TagEntity(name = LONE_SURROGATE_A))

            val second = runCatching { db.tagDao().insert(TagEntity(name = LONE_SURROGATE_B)) }.exceptionOrNull()

            assertNotEquals(LONE_SURROGATE_A, LONE_SURROGATE_B)
            assertTrue("expected the index to see one name, got $second", second is SQLiteConstraintException)
        }

    /**
     * So a file holding them is refused by the validator, before the wipe — not by the insert
     * after it, which used to roll the rows back but leave the live session and its draft
     * cleared.
     */
    @Test
    fun `a restore holding names SQLite would store as one is refused before anything is cleared`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()

            val progress = restoreOver(escapeLoneSurrogates = true) { document ->
                document.copy(
                    tag = document.tag.mapIndexed { index, row ->
                        row.copy(name = if (index == 0) LONE_SURROGATE_A else LONE_SURROGATE_B)
                    },
                )
            }

            assertRefused(progress, "tag.name of row 1")
            assertEquals(before, db.dumpEverything())
            assertEquals("the draft must not be cleared for a file the validator refuses", 0, drafts.clearCount)
        }

    @Test
    fun `a restored second session in progress is refused and changes nothing`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()

            val progress = restoreOver { document ->
                document.copy(workoutSession = document.workoutSession + sessionRow(id = 9, inProgress = true))
            }

            assertRefused(progress, "workout_session holds 2 sessions in progress")
            assertEquals(before, db.dumpEverything())
        }

    @Test
    fun `a restore holding one Health Connect record twice is refused and changes nothing`() =
        runBlocking {
            db.seedEveryTable()
            val before = db.dumpEverything()

            val progress = restoreOver { document ->
                val imported = document.workoutSession.single { it.healthConnectId != null }
                document.copy(workoutSession = document.workoutSession + imported.copy(id = 9))
            }

            assertRefused(progress, "workout_session holds two rows with the same health_connect_id")
            assertEquals(before, db.dumpEverything())
        }

    /**
     * Every sum the History list, the Stats screen and the dashboard read, over a history whose
     * sets add up past a `Long`. Each is held at `Long.MAX_VALUE` rather than throwing.
     */
    private suspend fun assertEverySumReads() {
        val sets = db.workoutSetDao()
        val end = FINISHED_AT / DAY_MS + 1
        val totals = sets.getTotalsBySession().first().single { it.sessionId == 1L }

        assertEquals(Long.MAX_VALUE, totals.totalVolume)
        assertEquals(Long.MAX_VALUE, totals.timedSeconds)
        assertEquals(Long.MAX_VALUE, totals.totalDistance)
        assertEquals(Long.MAX_VALUE, sets.getVolumeByWeek(0, end).first().single().totalVolume)
        assertEquals(Long.MAX_VALUE, sets.getVolumeIn(0, end).first())
        sets.getVolumeByMuscleGroup(0, end).first().forEach { assertEquals(Long.MAX_VALUE, it.totalVolume) }
        assertEquals(Long.MAX_VALUE, sets.getAttemptsByExercise(1, 0, end).first().single().totalVolume)
        db.workoutSessionDao().getTrainingMillis(0, end).first()
    }

    private suspend fun assertTemplateEstimateUsesTheDefaultRest() {
        val exercise = exercises.getById(1).first()!!
        val plan = TemplateExercise(templateId = 1, exerciseId = 1, position = 0, sets = List(3) { PlannedSet() })
        val defaultRest = preferences.observe().first().defaultRestDuration

        val estimate = TemplateDuration.estimate(listOf(plan), defaultRest, mapOf(1L to exercise))

        assertEquals(TemplateDuration.WORK_PER_SET * 3 + defaultRest * 2, estimate)
    }

    /** What Home, the quest log and the post-session cascade do with the progress row. */
    private suspend fun assertProgressReadsAndMoves() {
        val standing = gamification.observeProgress().first()!!
        assertTrue(standing.level >= XpCalculator.FIRST_LEVEL)
        assertTrue(standing.xpIntoLevel >= 0)
        assertTrue(standing.xpForNextLevel > 0)
        assertTrue(standing.levelFraction in 0f..1f)
        gamification.observeStreak().first()

        val award = gamification.addXp(XpCalculator.XP_PER_SESSION, XpCalculator::levelForTotalXp)
        assertTrue(award.totalXp in 0..XpCalculator.MAX_TOTAL_XP)

        gamification.updateStreak { state ->
            StreakCalculator.advance(state, Instant.ofEpochMilli(FINISHED_AT + 1), StreakCalculator.DEFAULT_WEEKLY_GOAL)
        }
        gamification.observeProgress().first()!!.levelFraction
    }

    private suspend fun assertANewSessionCanBeLogged() {
        val id = db.workoutSessionDao().insert(session(createdAt = FINISHED_AT))
        assertTrue("a new session got id $id", id > 0)
    }

    /** Seeds every table, exports it, edits the file with [edit] and restores the result. */
    private suspend fun restore(edit: (ExportDocument) -> ExportDocument): ImportProgress {
        db.seedEveryTable()
        return restoreOver(edit = edit)
    }

    /**
     * Exports what the database holds now, edits it with [edit] and restores it over the top.
     *
     * @param escapeLoneSurrogates write every unpaired surrogate as a `\uD800`-style escape.
     *   That is the only way one reaches the decoder: the encoder writes text as it is, and
     *   UTF-8 has no bytes for half a pair — the file would hold `?` in its place.
     */
    private suspend fun restoreOver(
        escapeLoneSurrogates: Boolean = false,
        edit: (ExportDocument) -> ExportDocument,
    ): ImportProgress {
        val text = StringBuilder()
        export(ExportSink { text.append(it) }, APP_VERSION).toList()
        val document = (ExportFormat.decode(source(text.toString())) as ExportFormat.Decoded.Success).document
        val edited = json.encodeToString(ExportDocument.serializer(), edit(document))
        return import(source(if (escapeLoneSurrogates) edited.escapingLoneSurrogates() else edited)).toList().last()
    }

    /** This text with each unpaired surrogate written as a JSON `\u` escape. */
    private fun String.escapingLoneSurrogates(): String =
        buildString {
            this@escapingLoneSurrogates.forEachIndexed { index, char ->
                val paired = when {
                    char.isHighSurrogate() -> this@escapingLoneSurrogates.getOrNull(index + 1)?.isLowSurrogate() == true
                    char.isLowSurrogate() -> this@escapingLoneSurrogates.getOrNull(index - 1)?.isHighSurrogate() == true
                    else -> true
                }
                if (paired) append(char) else append("\\u%04x".format(char.code))
            }
        }

    private fun source(text: String) = ImportSource { text.byteInputStream() }

    private fun assertComplete(progress: ImportProgress) {
        assertTrue("expected the restore to complete, was $progress", progress is ImportProgress.Complete)
    }

    private fun assertRefused(
        progress: ImportProgress,
        prefix: String,
    ) {
        val error = (progress as? ImportProgress.Failed)?.error
        assertTrue("expected a constraint violation, was $progress", error is ImportError.ConstraintViolation)
        val reason = (error as ImportError.ConstraintViolation).reason
        assertTrue("expected the reason to start with $prefix, was: $reason", reason.startsWith(prefix))
    }

    /** The fixture's performed set made too heavy, long and far to sum, and a second one like it. */
    private fun List<WorkoutSetRow>.overflowing(): List<WorkoutSetRow> {
        val heavy = first { it.id == 1L }.copy(reps = 1, weight = HUGE, duration = HUGE, distance = HUGE)
        return map { if (it.id == heavy.id) heavy else it } + heavy.copy(id = 3, setNumber = 2)
    }

    private fun sessionRow(
        id: Long,
        inProgress: Boolean = false,
    ) = WorkoutSessionRow(
        id = id,
        completedAt = if (inProgress) null else FINISHED_AT,
        isInProgress = inProgress,
        lastActiveAt = FINISHED_AT,
        createdAt = STARTED_AT,
        updatedAt = FINISHED_AT,
    )

    private companion object {
        const val EXPORTED_AT = 1_788_686_970_491L
        const val APP_VERSION = "1.0-debug"
        const val DAY_MS = 86_400_000L

        /** More than half of `Long.MAX_VALUE`: any two of these overflow an integer sum. */
        const val HUGE = 5_000_000_000_000_000_000L

        /**
         * An unpaired high surrogate before `A` (U+0041) and before `с` (U+0441). SQLite's UTF-16
         * reader pairs the surrogate with whatever follows it and keeps that character's low ten
         * bits, and the two share them: both come out as U+10041.
         */
        const val LONE_SURROGATE_A = "\uD800A"
        const val LONE_SURROGATE_B = "\uD800\u0441"

        /** −10¹⁷ ms, three million years before the epoch: the issue's own example. */
        const val ANCIENT = -100_000_000_000_000_000L

        val json = Json {
            encodeDefaults = true
            explicitNulls = true
        }
    }
}
