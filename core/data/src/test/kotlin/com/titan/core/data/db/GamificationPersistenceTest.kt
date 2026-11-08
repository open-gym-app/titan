package com.titan.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.titan.core.data.db.entity.UserProgressEntity
import com.titan.core.data.repository.CollectedItemRepositoryImpl
import com.titan.core.data.repository.GamificationRepositoryImpl
import com.titan.core.domain.gamification.StreakCalculator
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.StreakProtection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.YearMonth

/**
 * That gamification progress is on the disk rather than in the process.
 *
 * Every other test in this package runs against an in-memory database, which cannot tell a row
 * that was written from one that was merely remembered. This one writes through a real file,
 * closes the database, opens the same file again and reads back — the closest a JVM test gets
 * to the app being killed, and the only shape that would catch progress living somewhere that
 * does not survive it.
 *
 * Robolectric rather than an instrumented test, per the project's testing strategy (#85):
 * `DatabaseMigrationTest` builds databases from files the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GamificationPersistenceTest {

    private companion object {
        const val DB_NAME = "gamification-persistence-test.db"
        val MONDAY_EVENING: Instant = Instant.parse("2026-03-02T19:00:00Z")
        val TUESDAY_EVENING: Instant = Instant.parse("2026-03-03T19:00:00Z")

        /** Mondays of the weeks after [MONDAY_EVENING]'s. */
        val SECOND_MONDAY: Instant = Instant.parse("2026-03-09T19:00:00Z")
        val THIRD_MONDAY: Instant = Instant.parse("2026-03-16T19:00:00Z")
        val FIFTH_MONDAY: Instant = Instant.parse("2026-03-30T19:00:00Z")

        /** A goal of one, so every session that counts is a week met and the streak moves. */
        const val GOAL = 1
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The database as the app builds it, minus the catalogue seed nothing here reads. */
    private fun open(): TitanDatabase =
        Room.databaseBuilder(context, TitanDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .addCallback(TitanDatabase.foreignKeysCallback)
            .addCallback(TitanDatabase.singleRowProgressCallback)
            .build()

    private fun TitanDatabase.gamification() =
        GamificationRepositoryImpl(
            userProgressDao = userProgressDao(),
            achievementDao = achievementDao(),
            sessionXpClaimDao = sessionXpClaimDao(),
            sessionLootClaimDao = sessionLootClaimDao(),
        )

    private fun TitanDatabase.inventory() = CollectedItemRepositoryImpl(collectedItemDao())

    @After
    fun deleteDatabaseFile() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `the XP total and the streak survive the database being closed and reopened`() =
        runBlocking {
            val first = open()
            first.gamification().addXp(XpCalculator.XP_PER_LEVEL * 2 + 40, XpCalculator::levelForTotalXp)
            first.gamification().updateStreak { state -> StreakCalculator.advance(state, MONDAY_EVENING, GOAL) }
            first.close()

            val reopened = open()
            val stored = reopened.userProgressDao().getOnce()
            reopened.close()

            assertEquals(XpCalculator.XP_PER_LEVEL * 2L + 40, stored?.totalXp)
            assertEquals(XpCalculator.levelForTotalXp(XpCalculator.XP_PER_LEVEL * 2L + 40), stored?.currentLevel)
            assertEquals(1, stored?.currentStreak)
            assertEquals(MONDAY_EVENING.toEpochMilli(), stored?.lastCountedAt)
            assertEquals(1, stored?.weekSessions)
            assertEquals(true, stored?.weekGoalMet)
        }

    @Test
    fun `a streak carries on across a restart rather than starting over`() =
        runBlocking {
            val first = open()
            first.gamification().updateStreak { state -> StreakCalculator.advance(state, MONDAY_EVENING, GOAL) }
            first.close()

            val reopened = open()
            val update = reopened.gamification()
                .updateStreak { state -> StreakCalculator.advance(state, SECOND_MONDAY, GOAL) }
            reopened.close()

            assertEquals(2, update.weeks)
        }

    @Test
    fun `an unlocked achievement is still unlocked on the next launch`() =
        runBlocking {
            val first = open()
            first.gamification().unlockAchievement("first_session", MONDAY_EVENING)
            first.close()

            val reopened = open()
            val unlockedAgain = reopened.gamification().unlockAchievement("first_session", TUESDAY_EVENING)
            val ids = reopened.gamification().unlockedAchievementIds()
            reopened.close()

            assertFalse("the unlock did not survive the restart, so it would be paid for twice", unlockedAgain)
            assertEquals(setOf("first_session"), ids)
        }

    @Test
    fun `a spent streak protection is still spent after a restart`() =
        runBlocking {
            val first = open()
            val gamification = first.gamification()
            gamification.updateStreak { state -> StreakCalculator.advance(state, MONDAY_EVENING, GOAL) }
            gamification.updateStreak { state -> StreakCalculator.advance(state, SECOND_MONDAY, GOAL) }
            gamification.updateStreak { state -> StreakCalculator.advance(state, THIRD_MONDAY, GOAL) }
            // The week of the 23rd missed: one protection buys the streak back.
            gamification.updateStreak { state -> StreakCalculator.advance(state, FIFTH_MONDAY, GOAL) }
            first.close()

            val reopened = open()
            val state = reopened.gamification().observeStreak().first()
            reopened.close()

            // The point of the test: an allowance held only in memory would come back full,
            // and a user could break a streak once a boot for free.
            assertEquals(StreakProtection.MONTHLY_ALLOWANCE - 1, state.protection.remaining)
            assertEquals(YearMonth.of(2026, 3), state.protection.period)
            assertEquals(FIFTH_MONDAY, state.protection.lastConsumedAt)
            assertEquals(4, state.currentStreak)
        }

    @Test
    fun `collected items are still held on the next launch, duplicates included`() =
        runBlocking {
            val first = open()
            first.inventory().recordDrop("pet_egg", "session_finished", MONDAY_EVENING)
            first.inventory().recordDrop("pet_egg", "new_gym_explored", TUESDAY_EVENING)
            first.close()

            val reopened = open()
            val held = reopened.inventory().observeRecent(limit = 10).first()
            reopened.close()

            // Two rows rather than one: an inventory that came back deduplicated would be a
            // set, and the second pull of a collectible would be lost on every restart.
            assertEquals(listOf("pet_egg", "pet_egg"), held.map { it.collectibleId })
            assertEquals(listOf("new_gym_explored", "session_finished"), held.map { it.source })
            assertEquals(listOf(TUESDAY_EVENING, MONDAY_EVENING), held.map { it.acquiredAt })
        }

    @Test
    fun `the single-row constraint is on the file, not just on the builder`() =
        runBlocking {
            val first = open()
            first.userProgressDao().upsert(UserProgressEntity(totalXp = 10))
            first.close()

            val reopened = open()
            // Raw SQL, because the constraint is what is under test rather than the DAO: the
            // table on the file has to refuse this whoever writes it.
            val failure = runCatching {
                reopened.openHelper.writableDatabase.execSQL(
                    "INSERT INTO user_progress " +
                        "(id, total_xp, current_level, current_streak, longest_streak, week_sessions, " +
                        "week_goal_met, protection_remaining) " +
                        "VALUES (2, 0, 1, 0, 0, 0, 0, 1)",
                )
            }.exceptionOrNull()
            reopened.close()

            assertTrue(
                "a second row reached the file: $failure",
                failure?.message.orEmpty().contains("CHECK constraint failed"),
            )
        }
}
