package com.titan.core.data.db

import androidx.paging.PagingConfig
import androidx.paging.testing.TestPager
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.ImportedExercise
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImportedWorkoutExercise
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.measureTime

/**
 * Query-time regression guards at the two scales [HistoryScales] names: the
 * 1,000-session / 50,000-set history the app is designed to stay responsive at,
 * and a multi-year power user well beyond it.
 *
 * These are *not* device benchmarks - they run on the Robolectric JVM runner
 * against in-memory SQLite, so the absolute numbers mean nothing next to a
 * phone's. What they catch is a query going from indexed to quadratic (a
 * dropped index, a join that stops using one, an aggregate moved out of SQL).
 * On-device numbers are #42's job.
 *
 * The budgets are milliseconds on an idle development machine, and they are scaled
 * to the machine the test is really on: CI moves between the homelab cluster and
 * GitHub-hosted runners that are several times slower, and it runs this beside
 * other test JVMs, so one fixed number is either too tight for the slow machine or
 * too loose to notice anything on the fast one. Every attempt first times
 * [CALIBRATION_SQL], which no schema or query change can affect, and allows the
 * query that much more. A regression slows the query and not the calibration, so
 * it fails on any machine, where a slow machine slows both and passes.
 */
class LargeHistoryBenchmarkTest : RoomTestBase() {

    private companion object {
        /**
         * The two scales, from [HistoryScales] in the shared `src/fixtures` source
         * directory that `:benchmark` also compiles - so the device benchmark measures
         * the same history this test guards, rather than one that merely resembles it.
         */
        val TARGET_SCALE = HistoryScales.TARGET
        val STRESS_SCALE = HistoryScales.STRESS

        /**
         * The bound from #14. At [TARGET_SCALE] the queries held to it measure
         * 11-45ms on the JVM runner, so it allows roughly three times the
         * slowest measurement before failing.
         */
        const val TARGET_BUDGET_MS = 150L

        /**
         * All-time volume by muscle group is the single heaviest query in the
         * app and gets its own budget: the join fans all 50,000 sets out across
         * `exercise_muscle_group` before aggregating, so it does three times the
         * row work of anything else here and measures ~145ms at [TARGET_SCALE].
         * A one-month window of the same query costs ~11ms - that one is held to
         * [TARGET_BUDGET_MS] below.
         */
        const val FULL_HISTORY_AGGREGATE_BUDGET_MS = 400L

        /** Comparable headroom over the stress fixture, whose slowest is ~100ms. */
        const val STRESS_BUDGET_MS = 500L

        /**
         * Fixed work inside SQLite that reads no table, so it costs the same whatever the
         * schema and the fixture become, and pays for the same engine the queries do.
         */
        const val CALIBRATION_SQL =
            "WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM n WHERE x < 200000) SELECT SUM(x) FROM n"

        /** What [CALIBRATION_SQL] measures on the machine the budgets above describe. */
        const val CALIBRATION_REFERENCE_MS = 25.0

        const val DAY_MILLIS = 86_400_000L

        /**
         * How many times a query may try to come in under its budget. The calibration follows
         * sustained load; a spike that lands on the query alone is what the other attempts are
         * for, and it is gone by the next one, where a regression is there on all of them.
         */
        const val MAX_ATTEMPTS = 3

        const val PAGE_SIZE = 20

        /**
         * A month's volume, which the quest board asks for after every replayed workout of an
         * import. It runs from the sessions in range and reaches their sets by foreign key, so
         * it measures under a millisecond at [STRESS_SCALE]; started from the `is_completed`
         * index instead it measures ~40 ms there, which is what this holds it away from.
         */
        const val PERIOD_VOLUME_BUDGET_MS = 8L

        /** How many workouts the import benchmark replays on top of the stress history. */
        const val IMPORTED_WORKOUTS = 1_000

        /**
         * What replaying one workout costs on the reference machine, with the stress history
         * already on the device. It measures ~8 ms with the period queries as they are, and
         * ~32 ms when one of them scans every set, so the budget sits between the two. A
         * coarse backstop: the query-level budgets above name the query that regressed.
         */
        const val IMPORT_BUDGET_MS_PER_WORKOUT = 15L

        /**
         * An exclusive end day past the last session [spec] seeds, for the range
         * aggregates: "the whole history" as a bounded window, since those queries no
         * longer take an open-ended one.
         */
        fun wholeHistoryEndDay(spec: HistorySpec): Long = WorkoutHistoryFixture.sessionEpochDay(spec.sessionCount) + 1
    }

    @Test
    fun `session history and volume queries stay fast at the target scale`(): Unit =
        runBlocking {
            val history = seedHistory(TARGET_SCALE)
            assertSeeded(TARGET_SCALE)
            warmUp()

            // The session-history screen: first page of the newest sessions.
            assertUnder("getAllPaginated first page", TARGET_BUDGET_MS) {
                TestPager(PagingConfig(pageSize = PAGE_SIZE), db.workoutSessionDao().getAllPaginated())
                    .refresh()
            }

            // The volume aggregates behind the progression screens, first over the
            // whole history - the worst case for each.
            assertUnder("getVolumeByWeek over the whole history", TARGET_BUDGET_MS) {
                db.workoutSetDao().getVolumeByWeek(0, wholeHistoryEndDay(TARGET_SCALE)).first()
            }
            // The consistency series. It groups `workout_session` rather than `workout_set`,
            // so it scans two orders of magnitude fewer rows than the query above - which is
            // the point of counting sessions in SQL rather than loading them.
            assertUnder("getTrainingByWeek over the whole history", TARGET_BUDGET_MS) {
                db.workoutSessionDao().getTrainingByWeek(0, wholeHistoryEndDay(TARGET_SCALE)).first()
            }
            assertUnder("getVolumeByMuscleGroup over the whole history", FULL_HISTORY_AGGREGATE_BUDGET_MS) {
                db.workoutSetDao()
                    .getVolumeByMuscleGroup(0, WorkoutHistoryFixture.sessionEpochDay(TARGET_SCALE.sessionCount))
                    .first()
            }
            // A one-month window: this is the one that needs the completed_at index
            // to prune, rather than scanning every set and discarding most of them.
            assertUnder("getVolumeByMuscleGroup over a one-month range", TARGET_BUDGET_MS) {
                db.workoutSetDao()
                    .getVolumeByMuscleGroup(0, WorkoutHistoryFixture.sessionEpochDay(30))
                    .first()
            }

            // Per-exercise history: every completed set for one exercise, all-time.
            val targetExerciseId = history.exerciseIds.first()
            assertUnder("getHistoryByExercisePaginated first page", TARGET_BUDGET_MS) {
                TestPager(
                    PagingConfig(pageSize = PAGE_SIZE),
                    db.workoutSetDao().getHistoryByExercisePaginated(targetExerciseId),
                ).refresh()
            }
        }

    @Test
    fun `hot-path queries stay fast against a large seeded history`(): Unit =
        runBlocking {
            val history = seedHistory(STRESS_SCALE)
            assertSeeded(STRESS_SCALE)
            warmUp()

            assertUnder("getActive", STRESS_BUDGET_MS) {
                db.workoutSessionDao().getActive().first()
            }

            val targetExerciseId = history.exerciseIds.first()
            assertUnder("getHistoryByExercise", STRESS_BUDGET_MS) {
                db.workoutSetDao().getHistoryByExercise(targetExerciseId).first()
            }

            assertUnder("getHistoryByExercisePaginated first page", STRESS_BUDGET_MS) {
                TestPager(
                    PagingConfig(pageSize = PAGE_SIZE),
                    db.workoutSetDao().getHistoryByExercisePaginated(targetExerciseId),
                ).refresh()
            }

            assertUnder("getAllPaginated first page", STRESS_BUDGET_MS) {
                TestPager(PagingConfig(pageSize = PAGE_SIZE), db.workoutSessionDao().getAllPaginated())
                    .refresh()
            }

            // Analytics aggregates: these scan a date range across all history, so
            // they are the queries most exposed to a missing completed_at index.
            assertUnder("getVolumeByWeek over the whole history", STRESS_BUDGET_MS) {
                db.workoutSetDao().getVolumeByWeek(0, wholeHistoryEndDay(STRESS_SCALE)).first()
            }

            assertUnder("getTrainingByWeek over the whole history", STRESS_BUDGET_MS) {
                db.workoutSessionDao().getTrainingByWeek(0, wholeHistoryEndDay(STRESS_SCALE)).first()
            }

            assertUnder("getVolumeByMuscleGroup over a one-month range", STRESS_BUDGET_MS) {
                db.workoutSetDao()
                    .getVolumeByMuscleGroup(0, WorkoutHistoryFixture.sessionEpochDay(30))
                    .first()
            }

            assertUnder("getRecentRecords", STRESS_BUDGET_MS) {
                db.personalRecordDao().getRecentRecords(limit = 10).first()
            }

            // The records list, which is the only paged read whose cost could grow with the size
            // of `personal_record` rather than with the page. The three below are the shapes that
            // tell those two apart: if the query windowed the whole table before paging it, the
            // deep page would cost what the whole history costs and the first page would too.
            assertUnder("getRecordsPage first page", STRESS_BUDGET_MS) {
                db.personalRecordDao().getRecordsPage(type = null, limit = PAGE_SIZE, offset = 0)
            }

            // 10,000 records in, near the oldest end of the fixture. A page here reads the same
            // twenty rows as the first one and must cost the same to fetch; this is the assertion
            // that fails if `LIMIT`/`OFFSET` moves back outside the derived table.
            assertUnder("getRecordsPage at offset 9,900", STRESS_BUDGET_MS) {
                db.personalRecordDao().getRecordsPage(type = null, limit = PAGE_SIZE, offset = 9_900)
            }

            // Filtered on the kind the fixture actually writes, so the page fills. The filter is
            // not sargable — see the query's KDoc — so this walks the `achieved_at` index and
            // stops at twenty matches rather than sorting the table.
            assertUnder("getRecordsPage filtered first page", STRESS_BUDGET_MS) {
                db.personalRecordDao().getRecordsPage(type = "MAX_WEIGHT", limit = PAGE_SIZE, offset = 0)
            }

            // The exercise library's two catalogue-wide reads, which run together on every open
            // of the Library tab.
            //
            // `getHeadlineRecords` is the one worth watching: it picks one record per exercise
            // with a subquery correlated on `exercise_id`, so its cost tracks the size of
            // `personal_record` rather than the catalogue, and this fixture is the only place
            // that says by how much.
            assertUnder("getHeadlineRecords", STRESS_BUDGET_MS) {
                db.personalRecordDao().getHeadlineRecords().first()
            }

            assertUnder("getAllMuscleGroupLinks", STRESS_BUDGET_MS) {
                db.exerciseDao().getAllMuscleGroupLinks().first()
            }

            // The quest board's month of volume, once per replayed workout during an import.
            assertUnder("getVolumeIn over a one-month range", PERIOD_VOLUME_BUDGET_MS) {
                db.workoutSetDao().getVolumeIn(0, WorkoutHistoryFixture.sessionEpochDay(30)).first()
            }

            // The session recap's records and the session list's badges. The first is bounded by
            // one session's sets; the second joins every record to its session, so like the
            // headline it grows with `personal_record`.
            assertUnder("getBySession", STRESS_BUDGET_MS) {
                db.personalRecordDao().getBySession(history.sessionIds.last()).first()
            }
            assertUnder("observeSessionIdsWithRecords", STRESS_BUDGET_MS) {
                db.personalRecordDao().observeSessionIdsWithRecords().first()
            }
        }

    @Test
    fun `importing onto a large history stays within its per-workout budget`(): Unit =
        runBlocking {
            seedHistory(STRESS_SCALE)
            val firstDay = WorkoutHistoryFixture.sessionEpochDay(STRESS_SCALE.sessionCount) + 1
            val bench = ImportedExercise(name = "bench press")
            val history = ImportedHistory(
                workouts = List(IMPORTED_WORKOUTS) { index ->
                    ImportedWorkout(
                        startedAt = (firstDay + index) * DAY_MILLIS,
                        durationSeconds = 3_600,
                        exercises = listOf(
                            ImportedWorkoutExercise(
                                bench,
                                List(10) { set -> ImportedSet(reps = 5, weight = 100_000_000L + set) },
                            ),
                        ),
                    )
                },
            )
            val stack = HistoryImportStack(
                db,
                InMemoryUserPreferencesRepository(),
                Clock { (firstDay + 2 * IMPORTED_WORKOUTS) * DAY_MILLIS },
            )
            warmUp()

            // One attempt only, since a second would import onto the first, so the machine is
            // calibrated on both sides of it: load that arrives during a run of several seconds
            // is load the calibration before it never saw.
            val slowdownBefore = machineSlowdown()
            val progress: List<HistoryImportProgress>
            val elapsedMs = measureTime { progress = stack.importHistory(history).toList() }.inWholeMilliseconds
            val slowdown = maxOf(slowdownBefore, machineSlowdown())
            val allowedMs = (IMPORT_BUDGET_MS_PER_WORKOUT * IMPORTED_WORKOUTS * slowdown).toLong()
            println(
                "[benchmark] import of $IMPORTED_WORKOUTS workouts: ${elapsedMs}ms (budget ${allowedMs}ms on this machine now)",
            )

            // Guards the guard: a refused or duplicate-skipped import would be fast and prove nothing.
            assertEquals(IMPORTED_WORKOUTS, (progress.last() as HistoryImportProgress.Complete).report.workoutsImported)
            assertTrue("the import took ${elapsedMs}ms against ${allowedMs}ms", elapsedMs < allowedMs)
        }

    /**
     * One untimed query and one untimed page first. Without them the first
     * measurement also pays for Room opening its statement cache, Paging and
     * [TestPager] loading their classes, and the JVM warming up - tens of
     * milliseconds on an idle machine and several times that on a loaded CI
     * runner, none of which belong to the query or to a regression in it. The
     * paged warm-up matters most: the same `getAllPaginated` refresh measures
     * ~35ms as the first pager in a JVM and ~4ms once the stack is loaded.
     */
    private suspend fun warmUp() {
        db.workoutSessionDao().getActive().first()
        TestPager(PagingConfig(pageSize = PAGE_SIZE), db.workoutSessionDao().getAllPaginated()).refresh()
    }

    /** Guards the guard: a fixture that silently seeded less would pass anything. */
    private fun assertSeeded(spec: HistorySpec) {
        assertEquals(spec.sessionExerciseCount, countRows("session_exercise"))
        assertEquals(spec.setCount, countRows("workout_set"))
    }

    private suspend fun assertUnder(
        label: String,
        budgetMs: Long,
        block: suspend () -> Unit,
    ) {
        val misses = mutableListOf<String>()
        repeat(MAX_ATTEMPTS) {
            val allowedMs = (budgetMs * machineSlowdown()).toLong()
            val elapsedMs = measureTime { block() }.inWholeMilliseconds
            println("[benchmark] $label: ${elapsedMs}ms (budget ${budgetMs}ms, ${allowedMs}ms on this machine now)")
            if (elapsedMs < allowedMs) return
            misses += "${elapsedMs}ms against ${allowedMs}ms"
        }
        fail("$label stayed over its ${budgetMs}ms budget at this scale, scaled to this machine: $misses")
    }

    /**
     * How many times slower than the budgets' reference this machine is running right now.
     *
     * Never under one: a machine faster than the reference keeps the budget as written rather
     * than a tighter one, which would make the test fail on noise the budget was sized to absorb.
     */
    private fun machineSlowdown(): Double {
        val calibrationMs = measureTime {
            db.query(CALIBRATION_SQL, emptyArray()).use { it.moveToFirst() }
        }.inWholeMilliseconds
        return maxOf(1.0, calibrationMs / CALIBRATION_REFERENCE_MS)
    }
}
