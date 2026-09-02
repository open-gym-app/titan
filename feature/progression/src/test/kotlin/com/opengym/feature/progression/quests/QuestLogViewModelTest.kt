package com.opengym.feature.progression.quests

import com.opengym.core.domain.gamification.Achievements
import com.opengym.core.domain.gamification.LevelTitles
import com.opengym.core.domain.gamification.Quests
import com.opengym.core.domain.model.Quest
import com.opengym.core.domain.model.RecordTally
import com.opengym.core.domain.model.UnlockedAchievement
import com.opengym.core.domain.model.UserProgress
import com.opengym.core.domain.usecase.QuestProgressUseCase
import com.opengym.feature.progression.stats.FakeProgressionStore
import kotlinx.coroutines.Dispatchers
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

/**
 * The quest log's state assembly: three reads combined, and the two catalogues walked.
 *
 * The board's *figures* are not exercised here — `QuestProgressUseCaseTest` owns which aggregate a
 * metric reads and over what range. What this covers is the part the ViewModel owns: that a grid
 * cell exists for every catalogue entry whether held or not, that the rank comes from the table,
 * and that a change in the store reaches the state without the screen being re-created.
 *
 * Collected in a launched job rather than read off `.value`, because `WhileSubscribed` produces
 * nothing at all without a subscriber — the same shape `PersonalRecordsViewModelTest` uses.
 */
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

    // ─── The achievements grid ──────────────────────────────────────────────────────────

    @Test
    fun `the grid has a cell for every catalogue achievement, held or not`() =
        runTest {
            gamification.unlocked.value = listOf(unlock("first_session"))

            val state = collect()

            assertEquals(Achievements.ALL.size, state().achievements.size)
            assertEquals(Achievements.ALL.map { it.id }, state().achievements.map { it.achievement.id })
        }

    @Test
    fun `an achievement on file is marked held and the rest are not`() =
        runTest {
            gamification.unlocked.value = listOf(unlock("first_session"), unlock("first_record"))

            val state = collect()

            assertEquals(2, state().unlockedCount)
            assertEquals(
                setOf("first_session", "first_record"),
                state().achievements.filter { it.isUnlocked }.map { it.achievement.id }.toSet(),
            )
        }

    @Test
    fun `an id on file that matches no catalogue entry puts no cell in the grid`() =
        runTest {
            // A milestone renamed without a migration. It must not produce a cell with no definition
            // behind it, and it must not inflate the count — see the note on [QuestLogViewModel].
            gamification.unlocked.value = listOf(unlock("a_milestone_from_an_older_build"))

            val state = collect()

            assertEquals(Achievements.ALL.size, state().achievements.size)
            assertEquals(0, state().unlockedCount)
        }

    // ─── The rank ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the rank comes from the table rather than from the level number`() =
        runTest {
            gamification.progress.value = UserProgress(totalXp = 1_240, currentLevel = 13)

            assertEquals(LevelTitles.STEEL_FORGED, collect()().levelTitle)
        }

    @Test
    fun `a level past the end of the table keeps the last rank`() =
        runTest {
            gamification.progress.value = UserProgress(totalXp = 99_999, currentLevel = 400)

            assertEquals(LevelTitles.LEGEND, collect()().levelTitle)
        }

    // ─── The zero state ─────────────────────────────────────────────────────────────────

    @Test
    fun `a brand-new user is level one, newcomer, with nothing held`() =
        runTest {
            val state = collect()

            assertEquals(1, state().progress.currentLevel)
            assertEquals(LevelTitles.NEWCOMER, state().levelTitle)
            assertEquals(0, state().unlockedCount)
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
            assertEquals(1, state().progress.currentLevel)

            gamification.progress.value = UserProgress(totalXp = 450, currentLevel = 5)

            assertEquals(5, state().progress.currentLevel)
            assertEquals(LevelTitles.IRON_DISCIPLE, state().levelTitle)
        }

    @Test
    fun `an achievement unlocked while the screen is open fills its cell in`() =
        runTest {
            val state = collect()
            assertEquals(0, state().unlockedCount)

            gamification.unlocked.value = listOf(unlock("first_session"))

            assertEquals(1, state().unlockedCount)
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

    /**
     * Starts collecting and hands back a reader for the latest state.
     *
     * A function rather than a captured value: `WhileSubscribed` only emits while something is
     * collecting, and every assertion here reads *after* the store has been written to.
     */
    private fun TestScope.collect(): () -> QuestLogUiState {
        val viewModel = QuestLogViewModel(
            gamificationRepository = gamification,
            questProgress = QuestProgressUseCase(
                progressionRepository = FakeProgressionStore(),
                personalRecordRepository = records,
                questRepository = completions,
            ),
        )
        // Unconfined, so the collection starts eagerly rather than being queued: without a
        // subscriber `WhileSubscribed` emits nothing at all, and every assertion below reads
        // the state after writing to a store.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
        return { viewModel.uiState.value }
    }

    private fun QuestLogUiState.questFor(quest: Quest) = quests.single { it.quest == quest }

    private fun unlock(id: String) = UnlockedAchievement(achievementId = id, unlockedAt = Instant.EPOCH)
}
