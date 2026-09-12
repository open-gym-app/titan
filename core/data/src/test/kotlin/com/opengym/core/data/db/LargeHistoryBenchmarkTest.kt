package com.opengym.core.data.db

import androidx.paging.PagingConfig
import androidx.paging.testing.TestPager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Query-time regression guards at the two scales [HistoryScales] names: the
 * 1,000-session / 50,000-set history the app is designed to stay responsive at,
 * and a multi-year power user well beyond it.
 *
 * These are *not* device benchmarks - they run on the Robolectric JVM runner
 * against in-memory SQLite, so the absolute numbers mean nothing next to a
 * phone's. What they catch is a query going from indexed to quadratic (a
 * dropped index, a join that stops using one, an aggregate moved out of SQL);
 * the budgets are set well above the measured times so ordinary CI-runner noise
 * cannot fail the build. On-device numbers are #42's job.
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

        const val PAGE_SIZE = 20

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

    private suspend fun <T> assertUnder(
        label: String,
        budgetMs: Long,
        block: suspend () -> T,
    ): T {
        val start = System.nanoTime()
        val result = block()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("[benchmark] $label: ${elapsedMs}ms (budget ${budgetMs}ms)")
        assertTrue(
            "$label took ${elapsedMs}ms, expected under ${budgetMs}ms at this scale",
            elapsedMs < budgetMs,
        )
        return result
    }
}
