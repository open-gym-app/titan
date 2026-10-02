package com.titan.feature.progression.quests

import com.titan.core.domain.gamification.Achievements
import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.gamification.LevelTitles
import com.titan.core.domain.gamification.Quests
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.DropSource
import com.titan.core.domain.model.Quest
import com.titan.core.domain.model.RecordTally
import com.titan.core.domain.model.StreakProtection
import com.titan.core.domain.model.StreakState
import com.titan.core.domain.model.UnlockedAchievement
import com.titan.core.domain.model.UserProgress
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.QuestProgressUseCase
import com.titan.feature.progression.stats.FakeProgressionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * The quest log's state assembly: five reads combined, and the catalogues walked.
 *
 * The board's *figures* are not exercised here — `QuestProgressUseCaseTest` owns which aggregate a
 * metric reads and over what range. What this covers is the part the ViewModel owns: that the
 * milestone count is held over the catalogue, that the rank comes from the table, and that a
 * change in the store reaches the state without the screen being re-created.
 *
 * Collected in a launched job rather than read off `.value`, because `WhileSubscribed` produces
 * nothing at all without a subscriber — the same shape `PersonalRecordsViewModelTest` uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuestLogViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private val gamification = FakeGamificationReads()
    private val records = FakeRecordReads()
    private val completions = FakeQuestCompletions()
    private val collected = FakeCollectedItemReads()

    /** Pinned to [NOW] so the month the allowance is replenished for never moves under a run. */
    private val clock = Clock { NOW.toEpochMilli() }

    // ─── The milestone count ────────────────────────────────────────────────────────────

    @Test
    fun `the milestone count is held over the whole catalogue`() =
        runTest {
            gamification.unlocked.value = listOf(unlock("first_session"), unlock("first_record"))

            val state = collect()

            assertEquals(2, state().achievementsUnlocked)
            assertEquals(Achievements.ALL.size, state().achievementsTotal)
        }

    @Test
    fun `an id on file that matches no catalogue entry does not inflate the count`() =
        runTest {
            // A milestone renamed without a migration. Counted against the catalogue rather than
            // taken as the row count, so it cannot push the numerator past the denominator —
            // see the note on [QuestLogViewModel]. The grid it would have drawn a cell in is
            // `AchievementsViewModel`'s, and `AchievementsViewModelTest` covers that half.
            gamification.unlocked.value = listOf(unlock("a_milestone_from_an_older_build"))

            val state = collect()

            assertEquals(0, state().achievementsUnlocked)
            assertEquals(Achievements.ALL.size, state().achievementsTotal)
        }

    // ─── The rank ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the rank comes from the table rather than from the level number`() =
        runTest {
            gamification.progress.value = UserProgress(totalXp = 1_240, level = 13)

            assertEquals(LevelTitles.STEEL_FORGED, collect()().levelTitle)
        }

    @Test
    fun `a level past the end of the table keeps the last rank`() =
        runTest {
            gamification.progress.value = UserProgress(totalXp = 99_999, level = 400)

            assertEquals(LevelTitles.LEGEND, collect()().levelTitle)
        }

    // ─── The zero state ─────────────────────────────────────────────────────────────────

    @Test
    fun `a brand-new user is level one, newcomer, with nothing held`() =
        runTest {
            val state = collect()

            assertEquals(1, state().progress.level)
            assertEquals(LevelTitles.NEWCOMER, state().levelTitle)
            assertEquals(0, state().achievementsUnlocked)
            assertFalse("a user who has never trained is not tying a streak", state().isOnBestStreak)
        }

    @Test
    fun `a brand-new user gets the whole board rather than an empty section`() =
        runTest {
            val state = collect()

            assertEquals(Quests.ALL, state().quests.map { it.quest })
            assertTrue("nothing should be complete", state().quests.none { it.isComplete })
            assertFalse(state().showsEmptyQuests)
        }

    // ─── The streak ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a run that ties the best is reported as the best`() =
        runTest {
            gamification.progress.value = UserProgress(currentStreak = 7, longestStreak = 7)

            assertTrue(collect()().isOnBestStreak)
        }

    @Test
    fun `a run short of the best is not`() =
        runTest {
            gamification.progress.value = UserProgress(currentStreak = 3, longestStreak = 21)

            assertFalse(collect()().isOnBestStreak)
        }

    // ─── Reactivity ─────────────────────────────────────────────────────────────────────

    @Test
    fun `xp banked while the screen is open moves the level and the rank`() =
        runTest {
            // #104's "update reactively", concretely: a workout finishing behind this screen.
            val state = collect()
            assertEquals(1, state().progress.level)

            gamification.progress.value = UserProgress(totalXp = 450, level = 5)

            assertEquals(5, state().progress.level)
            assertEquals(LevelTitles.IRON_DISCIPLE, state().levelTitle)
        }

    @Test
    fun `an achievement unlocked while the screen is open moves the count`() =
        runTest {
            val state = collect()
            assertEquals(0, state().achievementsUnlocked)

            gamification.unlocked.value = listOf(unlock("first_session"))

            assertEquals(1, state().achievementsUnlocked)
        }

    @Test
    fun `a quest completed while the screen is open is marked complete`() =
        runTest {
            val state = collect()
            assertTrue(state().quests.none { it.isComplete })

            completions.completed.value = setOf(Quests.WEEKLY_RECORD.id)

            assertEquals(
                listOf(Quests.WEEKLY_RECORD),
                state().quests.filter { it.isComplete }.map { it.quest },
            )
        }

    @Test
    fun `a record broken while the screen is open moves the quest that counts records`() =
        runTest {
            val state = collect()
            assertEquals(0, state().questFor(Quests.WEEKLY_RECORD).progress)

            records.tally.value = RecordTally(records = 3, exercises = 2)

            assertEquals(3, state().questFor(Quests.WEEKLY_RECORD).progress)
            assertEquals(3, state().questFor(Quests.MONTHLY_RECORDS).progress)
        }

    // ─── Streak protection ──────────────────────────────────────────────────────────────

    @Test
    fun `the stored allowance is carried through when it is already this month's`() =
        runTest {
            gamification.streak.value = StreakState(
                protection = StreakProtection(remaining = 1, period = THIS_MONTH),
            )

            val state = collect()

            assertEquals(1, state().protection.remaining)
        }

    @Test
    fun `an allowance stamped with an earlier month is replenished for the month being drawn`() =
        runTest {
            // The store applies the rollover when a session is *written*, so a user who has not
            // trained this month still has last month's remainder on file. Replenishing it is the
            // consumer's job, which `observeStreak` says in as many words — and this is it.
            gamification.streak.value = StreakState(
                protection = StreakProtection(remaining = 0, period = THIS_MONTH.minusMonths(1)),
            )

            val state = collect()

            assertEquals(StreakProtection.MONTHLY_ALLOWANCE, state().protection.remaining)
            assertEquals(THIS_MONTH, state().protection.period)
        }

    @Test
    fun `a protection spent while the screen is open lowers the count`() =
        runTest {
            gamification.streak.value = StreakState(
                protection = StreakProtection(remaining = 3, period = THIS_MONTH),
            )
            val state = collect()
            assertEquals(3, state().protection.remaining)

            gamification.streak.value = StreakState(
                protection = StreakProtection(remaining = 2, period = THIS_MONTH),
            )

            assertEquals(2, state().protection.remaining)
        }

    // ─── Recent loot ────────────────────────────────────────────────────────────────────

    @Test
    fun `a drop is paired with the catalogue entry that describes it`() =
        runTest {
            collected.recent.value = listOf(drop(Collectibles.PET_EGG.id))

            val state = collect()

            assertEquals(Collectibles.PET_EGG, state().recentLoot.single().collectible)
            assertTrue(state().showsLoot)
        }

    @Test
    fun `a drop this build cannot name is kept, without a catalogue entry`() =
        runTest {
            // A newer build's collectible, or one renamed without a migration. It is loot the
            // user holds, so the row survives and draws generically rather than being dropped.
            collected.recent.value = listOf(drop("a_collectible_from_the_future"))

            val state = collect()

            assertEquals(1, state().recentLoot.size)
            assertEquals(null, state().recentLoot.single().collectible)
        }

    @Test
    fun `a user with no drops does not get a loot section`() =
        runTest {
            val state = collect()

            assertTrue(state().recentLoot.isEmpty())
            assertFalse(state().showsLoot)
        }

    @Test
    fun `the loot read is asked for a bounded number of rows`() =
        runTest {
            // Under 1 would be read by the store as no limit at all, handing back an inventory
            // that grows for the life of the install — see `CollectedItemRepository.observeRecent`.
            collect()

            assertTrue((collected.requestedLimit ?: 0) >= 1)
        }

    @Test
    fun `a drop earned while the screen is open appears without it being re-created`() =
        runTest {
            val state = collect()
            assertFalse(state().showsLoot)

            collected.recent.value = listOf(drop(Collectibles.IRON_CROWN.id))

            assertTrue(state().showsLoot)
            assertEquals(Collectibles.IRON_CROWN, state().recentLoot.single().collectible)
        }

    /**
     * Starts collecting and hands back a reader for the latest state.
     *
     * A function rather than a captured value: `WhileSubscribed` only emits while something is
     * collecting, and every assertion here reads *after* the store has been written to.
     */
    private fun TestScope.collect(): () -> QuestLogUiState {
        val viewModel = QuestLogViewModel(
            gamificationRepository = gamification,
            collectedItems = collected,
            questProgress = QuestProgressUseCase(
                progressionRepository = FakeProgressionStore(),
                personalRecordRepository = records,
                questRepository = completions,
            ),
            clock = clock,
        )
        // Unconfined, so the collection starts eagerly rather than being queued: without a
        // subscriber `WhileSubscribed` emits nothing at all, and every assertion below reads
        // the state after writing to a store.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return { viewModel.uiState.value }
    }

    private fun QuestLogUiState.questFor(quest: Quest) = quests.single { it.quest == quest }

    private fun unlock(id: String) = UnlockedAchievement(achievementId = id, unlockedAt = Instant.EPOCH)

    private fun drop(
        collectibleId: String,
        id: Long = 1,
    ) = CollectedItem(
        id = id,
        collectibleId = collectibleId,
        source = DropSource.SESSION_FINISHED.key,
        acquiredAt = NOW,
    )

    private companion object {

        /** Mid-March 2026, so [THIS_MONTH] is a month the fixtures can be stamped against. */
        val NOW: Instant = YearMonth.of(2026, 3).atDay(14).atStartOfDay(ZoneOffset.UTC).toInstant()

        val THIS_MONTH: YearMonth = YearMonth.of(2026, 3)
    }
}
