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
 * the first would be measuring the first-run questions instead of the screen it named. This is the one
 * piece of app state the seeder touches that is not history.
 *
 * ### It rebases the history onto "now"
 *
 * [WorkoutHistoryFixture]'s timestamps are a pure function of a row's position — deliberately,
 * so a JVM test can assert what it reads back without a copy of everything the fixture wrote —
 * which anchors the whole history at the Unix epoch rather than near the wall clock. That is
 * invisible to a JVM test, which only ever queries relative to [WorkoutHistoryFixture]'s own
 * day numbering, but not to a screen: the Stats hub's default window is the trailing quarter,
 * and a history that ends in 1972 draws that window empty. Shifting every seeded timestamp by
 * one constant, after the fixture has written them, keeps the fixture itself oblivious to the
 * wall clock while landing the most recent session on it.
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
        rebaseHistoryOntoNow(spec)
        return countRows(WORKOUT_SET_TABLE)
    }

    /**
     * Slides every seeded timestamp forward by the same offset, so the most recent seeded
     * session — [WorkoutHistoryFixture.sessionCreatedAt] of [HistorySpec.sessionCount], the
     * active session `seedActiveSession` adds — lands on the current wall-clock time. Every
     * gap the fixture wrote stays exactly as it was; only the calendar it sits on moves.
     */
    private fun rebaseHistoryOntoNow(spec: HistorySpec) {
        val offsetMillis = System.currentTimeMillis() - WorkoutHistoryFixture.sessionCreatedAt(spec.sessionCount)
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "UPDATE workout_session SET created_at = created_at + ?, updated_at = updated_at + ?, " +
                "last_active_at = last_active_at + ?, " +
                "completed_at = CASE WHEN completed_at IS NULL THEN NULL ELSE completed_at + ? END",
            arrayOf(offsetMillis, offsetMillis, offsetMillis, offsetMillis),
        )
        db.execSQL(
            "UPDATE workout_set SET completed_at = completed_at + ?, created_at = created_at + ?, " +
                "updated_at = updated_at + ?",
            arrayOf(offsetMillis, offsetMillis, offsetMillis),
        )
        db.execSQL(
            "UPDATE session_exercise SET created_at = created_at + ?, updated_at = updated_at + ?",
            arrayOf(offsetMillis, offsetMillis),
        )
        db.execSQL("UPDATE personal_record SET achieved_at = achieved_at + ?", arrayOf(offsetMillis))
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
