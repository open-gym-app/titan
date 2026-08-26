package com.opengym.core.data.db

import androidx.paging.PagingConfig
import androidx.paging.testing.TestPager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Query-time regression guards at two scales: [TARGET_SCALE], the 1,000-session
 * / 50,000-set history the app is designed to stay responsive at, and
 * [STRESS_SCALE], a multi-year power user well beyond it.
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
        /** The scale target the app commits to: 1,000 sessions, 50,000 sets. */
        val TARGET_SCALE = HistorySpec(
            sessionCount = 1_000,
            exercisesPerSession = 5,
            setsPerExercise = 10,
        )

        /** Ten times the sessions, so a query that scales badly is obvious. */
        val STRESS_SCALE = HistorySpec(
            sessionCount = 10_000,
            exercisesPerSession = 3,
            setsPerExercise = 4,
        )

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
                db.workoutSetDao().getVolumeByWeek(0).first()
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
                db.workoutSetDao().getVolumeByWeek(0).first()
            }

            assertUnder("getVolumeByMuscleGroup over a one-month range", STRESS_BUDGET_MS) {
                db.workoutSetDao()
                    .getVolumeByMuscleGroup(0, WorkoutHistoryFixture.sessionEpochDay(30))
                    .first()
            }

            assertUnder("getRecentRecords", STRESS_BUDGET_MS) {
                db.personalRecordDao().getRecentRecords(limit = 10).first()
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
     * One untimed query first. Without it the first measurement also pays for
     * Room opening its statement cache and the JVM warming up - tens of
     * milliseconds that belong to neither the query nor a regression in it.
     */
    private suspend fun warmUp() {
        db.workoutSessionDao().getActive().first()
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
