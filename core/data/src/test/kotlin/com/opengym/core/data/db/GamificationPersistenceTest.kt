package com.opengym.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.opengym.core.data.db.entity.UserProgressEntity
import com.opengym.core.data.repository.GamificationRepositoryImpl
import com.opengym.core.domain.gamification.StreakCalculator
import com.opengym.core.domain.gamification.XpCalculator
import com.opengym.core.domain.model.StreakProtection
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
        val WEDNESDAY_EVENING: Instant = Instant.parse("2026-03-04T19:00:00Z")
        val FRIDAY_EVENING: Instant = Instant.parse("2026-03-06T19:00:00Z")
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The database as the app builds it, minus the catalogue seed nothing here reads. */
    private fun open(): OpenGymDatabase =
        Room.databaseBuilder(context, OpenGymDatabase::class.java, DB_NAME)
            .allowMainThreadQueries()
            .addCallback(OpenGymDatabase.foreignKeysCallback)
            .addCallback(OpenGymDatabase.singleRowProgressCallback)
            .build()

    private fun OpenGymDatabase.gamification() =
        GamificationRepositoryImpl(
            userProgressDao = userProgressDao(),
            achievementDao = achievementDao(),
            sessionXpClaimDao = sessionXpClaimDao(),
        )

    @After
    fun deleteDatabaseFile() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `the XP total and the streak survive the database being closed and reopened`() =
        runBlocking {
            val first = open()
            first.gamification().addXp(XpCalculator.XP_PER_LEVEL * 2 + 40, XpCalculator::levelForTotalXp)
            first.gamification().updateStreak { state -> StreakCalculator.advance(state, MONDAY_EVENING) }
            first.close()

            val reopened = open()
            val stored = reopened.userProgressDao().getOnce()
            reopened.close()

            assertEquals(XpCalculator.XP_PER_LEVEL * 2L + 40, stored?.totalXp)
            assertEquals(XpCalculator.levelForTotalXp(XpCalculator.XP_PER_LEVEL * 2L + 40), stored?.currentLevel)
            assertEquals(1, stored?.currentStreak)
            assertEquals(MONDAY_EVENING.toEpochMilli(), stored?.lastCountedAt)
        }

    @Test
    fun `a streak carries on across a restart rather than starting over`() =
        runBlocking {
            val first = open()
            first.gamification().updateStreak { state -> StreakCalculator.advance(state, MONDAY_EVENING) }
            first.close()

            val reopened = open()
            val update = reopened.gamification()
                .updateStreak { state -> StreakCalculator.advance(state, TUESDAY_EVENING) }
            reopened.close()

            assertEquals(2, update.days)
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
            gamification.updateStreak { state -> StreakCalculator.advance(state, MONDAY_EVENING) }
            gamification.updateStreak { state -> StreakCalculator.advance(state, TUESDAY_EVENING) }
            gamification.updateStreak { state -> StreakCalculator.advance(state, WEDNESDAY_EVENING) }
            // The Thursday missed: one protection buys the streak back.
            gamification.updateStreak { state -> StreakCalculator.advance(state, FRIDAY_EVENING) }
            first.close()

            val reopened = open()
            val state = reopened.gamification().observeStreak().first()
            reopened.close()

            // The point of the test: an allowance held only in memory would come back full,
            // and a user could break a streak once a boot for free.
            assertEquals(StreakProtection.MONTHLY_ALLOWANCE - 1, state.protection.remaining)
            assertEquals(YearMonth.of(2026, 3), state.protection.period)
            assertEquals(FRIDAY_EVENING, state.protection.lastConsumedAt)
            assertEquals(4, state.currentStreak)
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
                        "(id, total_xp, current_level, current_streak, longest_streak, protection_remaining) " +
                        "VALUES (2, 0, 1, 0, 0, 3)",
                )
            }.exceptionOrNull()
            reopened.close()

            assertTrue(
                "a second row reached the file: $failure",
                failure?.message.orEmpty().contains("CHECK constraint failed"),
            )
        }
}
