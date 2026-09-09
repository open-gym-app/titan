package com.opengym.core.data.benchmark

import com.opengym.core.data.db.HistorySpec
import com.opengym.core.data.db.OpenGymDatabase
import com.opengym.core.data.db.WorkoutHistoryFixture
import com.opengym.core.domain.repository.UserPreferencesRepository

/**
 * Puts a real history on the device, so `:benchmark` measures the app a three-year user has
 * rather than the app a fresh install has.
 *
 * It writes through [WorkoutHistoryFixture] — the same class `LargeHistoryBenchmarkTest` seeds
 * its in-memory database with, compiled from the shared `src/fixtures` directory — which is
 * what makes "at target scale" mean one thing in the JVM guard and on the phone.
 *
 * ### It clears the database first
 *
 * A fresh install already has the bundled exercise catalogue in it, and the fixture writes a
 * catalogue of its own; left together they would be two overlapping taxonomies and a set of
 * measurements that depended on which of them a query happened to touch. Clearing first is
 * what makes the device's database *the fixture's* database, row for row — the same
 * [HistorySpec.setCount] the JVM test asserts on.
 *
 * The exercise catalogue not surviving that is deliberate and not a loss: the fixture seeds
 * twenty exercises linked to seven muscle groups, which is everything the benchmarked screens
 * read, and it does it identically on every device.
 *
 * ### It marks onboarding complete
 *
 * A launch with the first-run flow still pending opens on onboarding, so every benchmark past
 * the first would be measuring four questions instead of the screen it named. This is the one
 * piece of app state the seeder touches that is not history.
 */
class BenchmarkHistorySeeder(
    private val database: OpenGymDatabase,
    private val preferences: UserPreferencesRepository,
) {

    /**
     * Seeds [spec], unless the database already holds exactly it.
     *
     * Idempotent because a Macrobenchmark's `setupBlock` runs once per iteration and writing
     * 50,000 rows ten times over would be most of the run. The check is the same one
     * `LargeHistoryBenchmarkTest.assertSeeded` makes — a row count, not a marker flag, so a
     * half-written database from an interrupted run seeds again rather than being trusted.
     *
     * @return how many `workout_set` rows the database holds afterwards.
     */
    suspend fun seed(spec: HistorySpec): Int {
        preferences.setOnboardingCompleted(true)

        if (countRows(WORKOUT_SET_TABLE) == spec.setCount) return spec.setCount

        database.clearAllTables()
        WorkoutHistoryFixture(database).seed(spec)
        return countRows(WORKOUT_SET_TABLE)
    }

    /** Rows in [table], through raw SQL: no DAO exposes a count over a whole table. */
    private fun countRows(table: String): Int =
        database.query("SELECT COUNT(*) FROM $table", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val WORKOUT_SET_TABLE = "workout_set"
    }
}
