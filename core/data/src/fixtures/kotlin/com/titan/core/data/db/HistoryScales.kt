package com.titan.core.data.db

/**
 * The two histories this project measures itself against, written down once.
 *
 * They live in `src/fixtures` — shared between the JVM unit tests and the `benchmark` variant
 * — rather than inside the test that first needed them, because two places measure them and
 * the numbers only mean anything if both measure the *same* one:
 *
 * - `LargeHistoryBenchmarkTest` seeds them into in-memory SQLite on the JVM and holds every
 *   query to a budget, which is the guard that runs in CI on every change.
 * - `:benchmark` seeds [TARGET] onto a real device through `BenchmarkHistorySeeder` and
 *   measures what the user actually waits for — cold start, a list, two charts.
 *
 * The JVM figures and the device figures are not comparable to each other and are not meant to
 * be. What matters is that "at target scale" means one thing in both.
 */
object HistoryScales {

    /**
     * The scale the app commits to: 1,000 sessions, 50,000 sets.
     *
     * A little under three years of training at one session a day — see
     * [WorkoutHistoryFixture.sessionCreatedAt] — which is a serious user's whole history and
     * the point past which "it is fast enough" stops being an assumption.
     */
    val TARGET = HistorySpec(
        sessionCount = 1_000,
        exercisesPerSession = 5,
        setsPerExercise = 10,
    )

    /**
     * Ten times the sessions, so a query that scales badly is obvious.
     *
     * JVM-only. It exists to make an accidental table scan visible against [TARGET], not to
     * describe a user; nothing on a device is held to it.
     */
    val STRESS = HistorySpec(
        sessionCount = 10_000,
        exercisesPerSession = 3,
        setsPerExercise = 4,
    )
}
