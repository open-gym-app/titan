package com.opengym.feature.history.sessions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.opengym.core.ui.component.TrainingDay
import com.opengym.core.ui.component.TrainingLoad
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.YearMonth
import kotlin.time.Duration.Companion.minutes

/**
 * The sessions screen, driven from hand-built states.
 *
 * The stateless half only — no ViewModel, no Hilt, no Room. Every case is "given this state,
 * is *this* drawn and does tapping it report *that*"; whether a filter actually narrows the
 * list is [SessionsViewModelTest]'s question, not this one's.
 */
@RunWith(RobolectricTestRunner::class)
class SessionsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<SessionsEvent>()
    private val opened = mutableListOf<Long>()
    private val openedTemplates = mutableListOf<Long>()
    private var started = 0
    private var newTemplates = 0

    // ─── The history tab ────────────────────────────────────────────────────────────────

    @Test
    fun `a populated history draws its months and its rows`() {
        setContent(populated())

        composeTestRule.onNodeWithText("APRIL 2026 · 2").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Push Day · Upper", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Leg Day", substring = true)
            .assertIsDisplayed()
        // `2 SESSIONS` in the header, unfiltered.
        composeTestRule.onNodeWithText("2 SESSIONS").assertIsDisplayed()
    }

    @Test
    fun `tapping a row opens that session`() {
        setContent(populated())

        composeTestRule.onNodeWithContentDescription("Push Day · Upper", substring = true)
            .performClick()

        assertEquals(listOf(1L), opened)
    }

    @Test
    fun `an empty history offers the way to fill it`() {
        setContent(SessionsUiState(isLoading = false, isLoadingTemplates = false))

        composeTestRule.onNodeWithText("No sessions yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("START A SESSION").performClick()

        assertEquals(1, started)
    }

    @Test
    fun `nothing is drawn while the read is still in flight`() {
        // Not the empty state: flashing "no sessions yet" over a history that is merely still
        // opening tells a user with four hundred of them that they have none.
        setContent(SessionsUiState(isLoading = true))

        composeTestRule.onNodeWithText("No sessions yet").assertDoesNotExist()
    }

    @Test
    fun `a filtered history that matches nothing offers the way back`() {
        setContent(populated().copy(query = "deadlift", months = emptyList(), matchCount = 0))

        composeTestRule.onNodeWithText("Nothing in that window").assertIsDisplayed()
        composeTestRule.onNodeWithText("CLEAR FILTERS").performClick()

        assertEquals(listOf(SessionsEvent.SearchAndFiltersCleared), events)
    }

    @Test
    fun `the header counts what survived the filters`() {
        setContent(populated().copy(query = "push", matchCount = 1))

        // The only thing on screen saying the list is narrower than it looks.
        composeTestRule.onNodeWithText("1 OF 2").assertIsDisplayed()
    }

    // ─── Search ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `typing in the search box reports every keystroke`() {
        setContent(populated())

        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNodeWithText("Search names and notes…").performTextInput("push")

        assertEquals(listOf(SessionsEvent.QueryChanged("push")), events)
    }

    @Test
    fun `closing the search box clears what was in it`() {
        // The alternative strands the user: a list narrowed to four rows, nothing on screen
        // saying by what, and the control that would say so now closed.
        setContent(populated().copy(query = "push", matchCount = 1))

        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNodeWithContentDescription("Search").performClick()

        assertEquals(listOf(SessionsEvent.QueryChanged("")), events)
    }

    // ─── The date window ────────────────────────────────────────────────────────────────

    @Test
    fun `the dates button opens the sheet on the window that is set`() {
        setContent(populated().copy(dateFilter = DateFilter.LastDays(7), matchCount = 1))

        composeTestRule.onNodeWithContentDescription("Dates").performClick()

        composeTestRule.onNodeWithText("LAST 7 DAYS").assertIsDisplayed()
        // What *choosing* one reports is [DateFilterPanelTest]'s question: the panel is split
        // out of the sheet precisely so a test can drive it without a sheet's animation and
        // window behaviour in the way, the same split `LibraryFilterSheet` makes.
    }

    @Test
    fun `a search and a window together still draw one list`() {
        // The screen's half of the intersection: both controls are on, the list is what the
        // ViewModel handed over, and neither control has hidden the other.
        setContent(
            populated().copy(
                query = "push",
                dateFilter = DateFilter.LastDays(7),
                months = populated().months.take(1),
                matchCount = 1,
            ),
        )

        composeTestRule.onNodeWithText("1 OF 2").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Push Day · Upper", substring = true)
            .assertIsDisplayed()
    }

    // ─── The tabs ───────────────────────────────────────────────────────────────────────

    @Test
    fun `choosing a tab reports it`() {
        setContent(populated())

        composeTestRule.onNodeWithText("CALENDAR").performClick()

        assertEquals(listOf(SessionsEvent.TabSelected(SessionsTab.CALENDAR)), events)
    }

    @Test
    fun `the templates tab lists the templates and counts them`() {
        setContent(populated(tab = SessionsTab.TEMPLATES))

        composeTestRule.onNodeWithText("1 TEMPLATE").assertIsDisplayed()
        // The row's own sentence, not a substring: the overflow button beside it also names the
        // template, and matching loosely would find both.
        composeTestRule.onNodeWithContentDescription("Push Day, 8 exercises, about 55 minutes")
            .assertIsDisplayed()
    }

    @Test
    fun `a templates tab with nothing in it says where templates come from`() {
        setContent(populated(tab = SessionsTab.TEMPLATES).copy(templates = emptyList()))

        composeTestRule.onNodeWithText("No templates yet").assertIsDisplayed()
    }

    @Test
    fun `tapping a template row opens its editor`() {
        setContent(populated(tab = SessionsTab.TEMPLATES))

        composeTestRule.onNodeWithContentDescription("Push Day, 8 exercises, about 55 minutes")
            .performClick()

        assertEquals(listOf(1L), openedTemplates)
    }

    @Test
    fun `the row's overflow offers rename, duplicate and delete`() {
        setContent(populated(tab = SessionsTab.TEMPLATES))

        composeTestRule.onNodeWithContentDescription("More for Push Day").performClick()
        composeTestRule.onNodeWithText("Rename").performClick()

        assertEquals(listOf(SessionsEvent.TemplateRenameRequested(1)), events)
    }

    @Test
    fun `duplicating proposes a name the ViewModel could not have worded`() {
        setContent(populated(tab = SessionsTab.TEMPLATES))

        composeTestRule.onNodeWithContentDescription("More for Push Day").performClick()
        composeTestRule.onNodeWithText("Duplicate").performClick()

        assertEquals(
            listOf(SessionsEvent.TemplateDuplicateRequested(1, "Push Day (copy)")),
            events,
        )
    }

    @Test
    fun `deleting from the overflow asks rather than deleting`() {
        setContent(populated(tab = SessionsTab.TEMPLATES))

        composeTestRule.onNodeWithContentDescription("More for Push Day").performClick()
        composeTestRule.onNodeWithText("Delete").performClick()

        assertEquals(listOf(SessionsEvent.TemplateDeleteRequested(1)), events)
    }

    @Test
    fun `the delete dialog names the template and says the history survives`() {
        setContent(
            populated(tab = SessionsTab.TEMPLATES).copy(
                templateAction = TemplateAction.Deleting(templateId = 1, name = "Push Day"),
            ),
        )

        composeTestRule.onNodeWithText("Delete “Push Day”?").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "The sessions you have already logged from it stay exactly where they are.",
        ).assertIsDisplayed()

        composeTestRule.onNodeWithText("Delete").performClick()
        assertEquals(listOf(SessionsEvent.TemplateDeleteConfirmed), events)
    }

    @Test
    fun `the rename dialog draws the name and reports what is typed into it`() {
        setContent(
            populated(tab = SessionsTab.TEMPLATES).copy(
                templateAction = TemplateAction.Naming(
                    templateId = 1,
                    kind = TemplateNaming.RENAME,
                    name = "Push Day",
                ),
            ),
        )

        composeTestRule.onNodeWithText("Rename template").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Template name").performTextInput("!")

        // The caret opens at the start of the field, so the character lands there.
        assertEquals(listOf(SessionsEvent.TemplateNameChanged("!Push Day")), events)
    }

    @Test
    fun `a name already in use is drawn under the field`() {
        setContent(
            populated(tab = SessionsTab.TEMPLATES).copy(
                templateAction = TemplateAction.Naming(
                    templateId = 1,
                    kind = TemplateNaming.DUPLICATE,
                    name = "Pull Day",
                    nameTaken = true,
                ),
            ),
        )

        composeTestRule.onNodeWithText("You already have a template with that name")
            .assertIsDisplayed()
    }

    @Test
    fun `the empty templates tab opens the editor on nothing`() {
        setContent(populated(tab = SessionsTab.TEMPLATES).copy(templates = emptyList()))

        composeTestRule.onNodeWithText("No templates yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("CREATE A TEMPLATE").performClick()

        assertEquals(1, newTemplates)
        // Not the session flow: the tab is a list of templates, and the button on it makes one.
        assertEquals(0, started)
    }

    @Test
    fun `the header's new action makes a template on the templates tab`() {
        // The same three characters as on the other two tabs and a different thing behind them,
        // because it makes whatever the tab is a list of. Without this the editor could only be
        // reached by tapping a template that already existed.
        setContent(populated(tab = SessionsTab.TEMPLATES))

        composeTestRule.onNodeWithContentDescription("New template").performClick()

        assertEquals(1, newTemplates)
        assertEquals(0, started)
    }

    @Test
    fun `the search box is not drawn over the calendar`() {
        // A search box over a month grid narrows nothing, so it is absent rather than inert.
        setContent(populated(tab = SessionsTab.CALENDAR))

        composeTestRule.onNodeWithContentDescription("Search").assertDoesNotExist()
    }

    // ─── The calendar ───────────────────────────────────────────────────────────────────

    @Test
    fun `the calendar heads itself with the month and its count`() {
        setContent(populated(tab = SessionsTab.CALENDAR))

        composeTestRule.onNodeWithText("APRIL 2026 · 2 SESSIONS").assertIsDisplayed()
    }

    @Test
    fun `tapping a trained day reports the day`() {
        setContent(populated(tab = SessionsTab.CALENDAR))

        composeTestRule
            .onNodeWithContentDescription(formatSessionDate(APRIL_28) + ", 1 session")
            .performClick()

        assertEquals(listOf(SessionsEvent.CalendarDaySelected(APRIL_28)), events)
    }

    @Test
    fun `paging the calendar reports which way`() {
        setContent(populated(tab = SessionsTab.CALENDAR))

        composeTestRule.onNodeWithContentDescription("Previous month").performClick()
        composeTestRule.onNodeWithContentDescription("Next month").performClick()

        assertEquals(
            listOf(
                SessionsEvent.CalendarMonthChanged(forward = false),
                SessionsEvent.CalendarMonthChanged(forward = true),
            ),
            events,
        )
    }

    @Test
    fun `a month with nothing in it says so rather than showing zeroed totals`() {
        setContent(
            populated(tab = SessionsTab.CALENDAR).copy(
                calendar = CalendarPage(month = YearMonth.of(2026, 3)),
            ),
        )

        composeTestRule.onNodeWithText("Nothing logged this month").assertIsDisplayed()
    }

    // ─── The header's way into a workout ────────────────────────────────────────────────

    @Test
    fun `the new-session action is offered on a tab that is not the history`() {
        // Search and dates belong to the history and are drawn only there; starting a workout
        // is not, and a user reading last month's calendar should not have to go back to the
        // history tab to begin one.
        setContent(populated(tab = SessionsTab.CALENDAR))

        composeTestRule.onNodeWithText("+ NEW").performClick()

        assertEquals(1, started)
    }

    private fun setContent(state: SessionsUiState) {
        composeTestRule.setContent {
            OpenGymTheme {
                SessionsScreen(
                    state = state,
                    onEvent = events::add,
                    onSessionClick = opened::add,
                    onStartSession = { started++ },
                    onTemplateClick = openedTemplates::add,
                    onNewTemplate = { newTemplates++ },
                )
            }
        }
    }

    /** Two sessions in one month, one template, and a calendar page with both days on it. */
    private fun populated(tab: SessionsTab = SessionsTab.HISTORY) = SessionsUiState(
        tab = tab,
        months = listOf(
            SessionMonth(
                month = YearMonth.of(2026, 4),
                sessions = listOf(
                    HistorySession(
                        id = 1,
                        name = "Push Day · Upper",
                        startedAt = middayOn(APRIL_28),
                        duration = 52.minutes,
                        totalSets = 12,
                        totalVolume = 4_820_000_000,
                        hasRecord = true,
                    ),
                    HistorySession(
                        id = 2,
                        name = "Leg Day",
                        startedAt = middayOn(APRIL_10),
                        duration = 64.minutes,
                        totalSets = 16,
                        totalVolume = 6_820_000_000,
                    ),
                ),
            ),
        ),
        sessionCount = 2,
        matchCount = 2,
        templates = listOf(
            com.opengym.core.domain.model.TemplateSummary(
                template = namedTemplate(1, "Push Day"),
                exerciseCount = 8,
                estimatedDuration = 55.minutes,
            ),
        ),
        calendar = CalendarPage(
            month = YearMonth.of(2026, 4),
            days = mapOf(
                10 to TrainingDay(sessionCount = 1, load = TrainingLoad.HEAVY),
                28 to TrainingDay(sessionCount = 1, load = TrainingLoad.LIGHT),
            ),
            sessionCount = 2,
            totalVolume = 11_640_000_000,
            totalDuration = 116.minutes,
        ),
        isLoading = false,
        isLoadingTemplates = false,
    )

    private companion object {
        val APRIL_28: LocalDate = LocalDate.of(2026, 4, 28)
        val APRIL_10: LocalDate = LocalDate.of(2026, 4, 10)
    }
}
