package com.opengym.core.data.db

import androidx.paging.PagingConfig
import androidx.paging.testing.TestPager
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seeds a multi-year, power-user-scale history (10k sessions, ~30k
 * session_exercise rows, 100k sets) and asserts the hot-path read queries
 * stay fast at that scale, so a missing index doesn't reach production
 * unnoticed. Thresholds are generous on purpose: this guards against gross
 * regressions (e.g. an unindexed join going quadratic), not micro-benchmarking.
 */
class LargeHistoryBenchmarkTest : RoomTestBase() {

    private companion object {
        const val EXERCISE_COUNT = 20
        const val SESSION_COUNT = 10_000
        const val EXERCISES_PER_SESSION = 3
        const val SETS_PER_SESSION = 10
        const val QUERY_BUDGET_MS = 500L
    }

    @Test
    fun `hot-path queries stay fast against a large seeded history`(): Unit = runBlocking {
        val exerciseIds = seedExercises()
        seedHistory(exerciseIds)

        assertUnder("getActive") {
            db.workoutSessionDao().getActive().first()
        }

        val targetExerciseId = exerciseIds.first()
        assertUnder("getHistoryByExercise") {
            db.workoutSetDao().getHistoryByExercise(targetExerciseId).first()
        }

        assertUnder("getHistoryByExercisePaginated first page") {
            val pager = TestPager(
                PagingConfig(pageSize = 20),
                db.workoutSetDao().getHistoryByExercisePaginated(targetExerciseId),
            )
            pager.refresh()
        }

        assertUnder("getAllPaginated first page") {
            val pager = TestPager(
                PagingConfig(pageSize = 20),
                db.workoutSessionDao().getAllPaginated(),
            )
            pager.refresh()
        }
    }

    private suspend fun seedExercises(): List<Long> =
        (0 until EXERCISE_COUNT).map { i -> db.exerciseDao().insert(exercise(name = "Exercise $i")) }

    private suspend fun seedHistory(exerciseIds: List<Long>) {
        db.withTransaction {
            repeat(SESSION_COUNT) { sessionIndex ->
                val createdAt = sessionIndex * 60_000L
                val sessionId = db.workoutSessionDao().insert(
                    session(isInProgress = false, lastActiveAt = createdAt, createdAt = createdAt),
                )
                val sessionExerciseIds = (0 until EXERCISES_PER_SESSION).map { slot ->
                    val exerciseId = exerciseIds[(sessionIndex + slot) % exerciseIds.size]
                    db.sessionExerciseDao().insert(sessionExercise(sessionId, exerciseId, position = slot))
                }
                repeat(SETS_PER_SESSION) { setIndex ->
                    val sessionExerciseId = sessionExerciseIds[setIndex % sessionExerciseIds.size]
                    db.workoutSetDao().insert(
                        workoutSet(
                            sessionExerciseId = sessionExerciseId,
                            setNumber = setIndex / sessionExerciseIds.size + 1,
                            isCompleted = true,
                            completedAt = createdAt + setIndex,
                        ),
                    )
                }
            }
            // Most-recently-active in-progress session, so getActive has real work to skip past.
            val lastActiveAt = SESSION_COUNT * 60_000L + 1
            db.workoutSessionDao().insert(
                session(isInProgress = true, lastActiveAt = lastActiveAt, createdAt = lastActiveAt),
            )
        }
    }

    private suspend fun <T> assertUnder(label: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "$label took ${elapsedMs}ms, expected under ${QUERY_BUDGET_MS}ms at this scale",
            elapsedMs < QUERY_BUDGET_MS,
        )
        return result
    }
}
