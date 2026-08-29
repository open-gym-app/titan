package com.opengym.feature.progression.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.opengym.core.domain.model.RecordTally
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.stats.component.StatsExploreActions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stats hub, driven from hand-built state.
 *
 * The stateless half, with no ViewModel and no Hilt graph behind it — the split
 * CONTRIBUTING.md § Screens exists for. What the drawn cards *look* like is the screenshot
 * references' job; this is about what the screen says and what it emits.
 */
@RunWith(RobolectricTestRunner::class)
class StatsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<StatsEvent>()
    private val opened = mutableListOf<String>()

    // ─── The timeframe selector ─────────────────────────────────────────────────────────

    @Test
    fun `every timeframe is offered, and the current one reads as taken`() {
        show(populated(timeframe = StatsTimeframe.QUARTER))

        listOf("7D", "30D", "90D", "1Y", "ALL").forEach { label ->
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
        }
        // As a radio group, so TalkBack says "2 of 5, selected" rather than naming five buttons.
        composeTestRule.onNodeWithContentDescription("Last 90 days").assertIsSelected()
    }

    @Test
    fun `tapping a timeframe asks for that window`() {
        show(populated())

        composeTestRule.onNodeWithText("30D").performClick()

        assertEquals(listOf(StatsEvent.TimeframeSelected(StatsTimeframe.MONTH)), events)
    }

    @Test
    fun `each timeframe can be chosen`() {
        show(populated())

        // One composition, five taps: the rule allows `setContent` once, and the five segments
        // are on screen together anyway — which is the point of a segmented control.
        StatsTimeframe.entries.forEach { timeframe ->
            composeTestRule.onNodeWithContentDescription(descriptionOf(timeframe)).performClick()
        }

        assertEquals(StatsTimeframe.entries.map(StatsEvent::TimeframeSelected), events)
    }

    // ─── The readings ───────────────────────────────────────────────────────────────────

    @Test
    fun `a populated window draws the hero, the grid and the muscle map`() {
        show(populated())

        // The hero's figure, formatted through the ambient unit system: 30,000,000 milligram-
        // reps is 30 kg.
        composeTestRule.onNodeWithText("30").assertIsDisplayed()
        // The rest of the page is below the fold on a test window, so it is scrolled to rather
        // than asserted displayed — the assertion is that the screen drew it, not where.
        composeTestRule.onNodeWithContentDescription("Sessions, 9 this period").performScrollTo()
        composeTestRule.onNodeWithContentDescription("PRs hit, 7 across 5 exercises").performScrollTo()
        composeTestRule.onNodeWithContentDescription("Chest, worked directly, 18 sets").performScrollTo()
    }

    @Test
    fun `the chart is announced as one readable series rather than as its marks`() {
        show(populated())

        // #144's criterion, reaching the screen: the card's chart carries its own sentence,
        // naming its range and its latest value rather than announcing twelve marks. (The
        // card's eyebrow carries the bare label, which is why this matches on the sentence.)
        composeTestRule
            .onNodeWithContentDescription("Cumulative volume, Apr to Apr.", substring = true)
            .assertExists()
    }

    @Test
    fun `the average per week shows its change against the previous period`() {
        show(populated())

        composeTestRule
            .onNodeWithContentDescription("Avg / week, 3 +0.4 vs prev")
            .performScrollTo()
    }

    // ─── The empty windows ──────────────────────────────────────────────────────────────

    @Test
    fun `a user who has never trained is asked to log a first session, not shown a chart`() {
        show(empty(StatsEmptiness.FIRST_RUN))

        composeTestRule.onNodeWithText("No training logged yet").assertIsDisplayed()
        // Not a zeroed hero: the card is absent rather than drawn empty.
        composeTestRule.onNodeWithContentDescription("Cumulative volume", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `a lapsed user is offered a wider window rather than told to start training`() {
        show(empty(StatsEmptiness.QUIET_PERIOD))

        composeTestRule.onNodeWithText("Nothing in this window").assertIsDisplayed()
        composeTestRule.onNodeWithText("SHOW ALL TIME").performScrollTo().performClick()

        assertEquals(listOf(StatsEvent.TimeframeSelected(StatsTimeframe.ALL)), events)
    }

    @Test
    fun `an empty window still offers every way out of the hub`() {
        show(empty(StatsEmptiness.FIRST_RUN))

        // Two of the five have no other entrance in the app, so hiding them behind "has
        // trained" would make the quest log unreachable for exactly the user it motivates.
        composeTestRule.onNodeWithText("Quest log").performScrollTo()
        composeTestRule.onNodeWithText("Body measurements").performScrollTo()
    }

    @Test
    fun `nothing below the selector is drawn while the reads are in flight`() {
        show(StatsUiState())

        composeTestRule.onNodeWithText("90D").assertIsDisplayed()
        composeTestRule.onNodeWithText("No training logged yet").assertDoesNotExist()
        composeTestRule.onNodeWithText("Quest log").assertDoesNotExist()
    }

    // ─── Explore ────────────────────────────────────────────────────────────────────────

    @Test
    fun `every explore row opens its own destination`() {
        show(populated())

        listOf(
            "Personal records" to "records",
            "Exercise progression" to "exercise",
            "Quest log" to "quests",
            "Body measurements" to "measurements",
            "Training calendar" to "calendar",
        ).forEach { (label, destination) ->
            opened.clear()
            composeTestRule.onNodeWithText(label).performScrollTo().performClick()
            assertEquals(listOf(destination), opened)
        }
    }

    // ─── Fixtures ───────────────────────────────────────────────────────────────────────

    private fun show(state: StatsUiState) {
        composeTestRule.setContent {
            OpenGymTheme {
                StatsScreen(state = state, onEvent = events::add, actions = actions())
            }
        }
    }

    private fun actions() =
        StatsExploreActions(
            onOpenRecords = { opened += "records" },
            onOpenExerciseProgression = { opened += "exercise" },
            onOpenQuestLog = { opened += "quests" },
            onOpenMeasurements = { opened += "measurements" },
            onOpenCalendar = { opened += "calendar" },
        )

    private fun populated(timeframe: StatsTimeframe = StatsTimeframe.QUARTER) =
        StatsUiState(
            timeframe = timeframe,
            isLoading = false,
            volume = VolumeState(
                total = 30_000_000L,
                weeks = listOf(
                    weeklyVolume(week = 0, volume = 10_000_000L),
                    weeklyVolume(week = 1, volume = 20_000_000L),
                ),
                changeRatio = 0.18,
            ),
            summary = SummaryState(
                sessions = 9,
                averagePerWeek = 3.0,
                averageChange = 0.4,
                records = RecordTally(records = 7, exercises = 5),
                trainingMillis = 4 * 60 * 60 * 1000L,
            ),
            muscles = listOf(trained(id = 1, name = "CHEST", sets = 18)),
        )

    private fun empty(emptiness: StatsEmptiness) =
        StatsUiState(
            timeframe = StatsTimeframe.QUARTER,
            isLoading = false,
            emptiness = emptiness,
        )

    private fun weeklyVolume(
        week: Int,
        volume: Long,
    ) = com.opengym.core.domain.model.WeeklyVolume(
        weekStart = java.time.LocalDate.of(2026, 4, 6).plusWeeks(week.toLong()),
        totalVolume = volume,
    )

    private fun descriptionOf(timeframe: StatsTimeframe): String =
        when (timeframe) {
            StatsTimeframe.WEEK -> "Last 7 days"
            StatsTimeframe.MONTH -> "Last 30 days"
            StatsTimeframe.QUARTER -> "Last 90 days"
            StatsTimeframe.YEAR -> "Last year"
            StatsTimeframe.ALL -> "All time"
        }
}
