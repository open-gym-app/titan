package com.opengym.feature.progression.achievements

import com.opengym.core.domain.gamification.Achievements
import com.opengym.core.domain.model.UnlockedAchievement
import com.opengym.feature.progression.quests.FakeGamificationReads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The achievements grid's state assembly: one read, and the catalogue walked against it.
 *
 * These three cases were `QuestLogViewModelTest`'s while the grid lived on that screen. They moved
 * with it, unchanged in substance: a cell for every catalogue entry held or not, an id on file
 * that matches nothing producing no cell, and an unlock reaching the grid while it is open.
 *
 * Collected in a launched job rather than read off `.value`, because `WhileSubscribed` produces
 * nothing at all without a subscriber.
 */
class AchievementsViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private val gamification = FakeGamificationReads()

    @Test
    fun `the grid has a cell for every catalogue achievement, held or not`() =
        runTest {
            gamification.unlocked.value = listOf(unlock("first_session"))

            val state = collect()

            assertEquals(Achievements.ALL.size, state().entries.size)
            assertEquals(Achievements.ALL.map { it.id }, state().entries.map { it.achievement.id })
        }

    @Test
    fun `an achievement on file is marked held and the rest are not`() =
        runTest {
            gamification.unlocked.value = listOf(unlock("first_session"), unlock("first_record"))

            val state = collect()

            assertEquals(2, state().unlockedCount)
            assertEquals(
                setOf("first_session", "first_record"),
                state().entries.filter { it.isUnlocked }.map { it.achievement.id }.toSet(),
            )
        }

    @Test
    fun `an id on file that matches no catalogue entry puts no cell in the grid`() =
        runTest {
            // A milestone renamed without a migration. It must not produce a cell with no
            // definition behind it, and it must not inflate the count.
            gamification.unlocked.value = listOf(unlock("a_milestone_from_an_older_build"))

            val state = collect()

            assertEquals(Achievements.ALL.size, state().entries.size)
            assertEquals(0, state().unlockedCount)
        }

    @Test
    fun `a brand-new user gets every cell, locked`() =
        runTest {
            val state = collect()

            assertEquals(0, state().unlockedCount)
            assertTrue("no cell is held", state().entries.none { it.isUnlocked })
            assertEquals(Achievements.ALL.size, state().entries.size)
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
    fun `nothing has answered before the first emission`() {
        val viewModel = AchievementsViewModel(gamificationRepository = gamification)

        assertTrue("the initial value is the loading one", viewModel.uiState.value.isLoading)
        assertEquals(emptyList<AchievementEntry>(), viewModel.uiState.value.entries)
    }

    /**
     * The state, kept live for the duration of the test.
     *
     * The collector runs on an unconfined dispatcher so `WhileSubscribed` starts sharing on the
     * spot; a standard one would leave the flow unsubscribed until the test scheduler advanced,
     * and every assertion would read the initial value.
     */
    private fun TestScope.collect(): () -> AchievementsUiState {
        val viewModel = AchievementsViewModel(gamificationRepository = gamification)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect { }
        }
        return { viewModel.uiState.value }
    }

    private fun unlock(id: String): UnlockedAchievement =
        UnlockedAchievement(achievementId = id, unlockedAt = Instant.EPOCH)
}
