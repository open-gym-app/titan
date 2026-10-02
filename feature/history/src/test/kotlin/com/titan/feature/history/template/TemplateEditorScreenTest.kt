package com.titan.feature.history.template

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

/**
 * The template editor, driven from hand-built states.
 *
 * The stateless half only — no ViewModel, no Hilt, no Room. Every case is "given this state, is
 * *this* drawn and does tapping it report *that*"; whether removing an exercise dissolves the
 * superset it was in is [TemplateEditorViewModelTest]'s question, not this one's.
 */
@RunWith(RobolectricTestRunner::class)
class TemplateEditorScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<TemplateEditorEvent>()
    private var exited = 0

    /** What each push of the picker asked for: true fills one slot, false adds to the plan. */
    private val pickerOpens = mutableListOf<Boolean>()

    @Test
    fun `the plan is drawn as a card per exercise under the template's own row`() {
        setContent(populated())

        composeTestRule.onNodeWithContentDescription(
            "Push Day · Upper, 3 exercises, about 45 minutes, Strength",
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Bench press").assertIsDisplayed()
        scrollTo(hasText("Cable fly")).assertIsDisplayed()
        scrollTo(hasText("Tricep pushdown")).assertIsDisplayed()
    }

    @Test
    fun `typing into a set reports which exercise, which set and which figure`() {
        setContent(populated())

        // Typed at the cursor, which starts before the "8" the field already holds.
        composeTestRule.onNodeWithContentDescription("Reps, set 2").performTextInput("1")

        assertEquals(
            listOf(TemplateEditorEvent.SetFieldChanged(1L, 12L, TemplateSetField.REPS, "18")),
            events,
        )
    }

    @Test
    fun `every planned set is drawn, not just a count of them`() {
        // The plan is four rows: a warm-up, two working sets and a drop set. The card used to
        // draw the number 4 in a box.
        setContent(populated())

        (1..4).forEach { setNumber ->
            scrollTo(hasContentDescription("Weight, set $setNumber")).assertIsDisplayed()
        }
    }

    @Test
    fun `adding a set reports the exercise it was added to`() {
        setContent(populated())

        // The action rather than a touch, as the × above: the control sits low on a scrolling
        // card, and where it lands under a finger is the layout's business.
        scrollTo(hasText("Add set")).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(TemplateEditorEvent.SetAdded(1L)), events)
    }

    @Test
    fun `removing an exercise reports it by key`() {
        setContent(populated())

        // The action rather than a touch: the × sits on the third card of a scrolling page, and
        // where it lands under a finger is the layout's business rather than this test's.
        scrollTo(hasContentDescription("Remove Cable fly from this template"))
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(TemplateEditorEvent.ExerciseRemoved(2L)), events)
    }

    @Test
    /*
     * A phone-sized window for this one test. Robolectric's default is 320 x 470 dp — smaller
     * than any phone shipped this decade — and with the save bar now holding the bottom of the
     * page the first join falls below the fold there, then scrolls up under the top bar, where
     * a tap lands on the bar instead of on it. Pinned per test rather than on the class: the
     * viewport is four times the surface to render and the whole class at that size runs the
     * test JVM out of heap. What the page looks like is answered on a device, not here.
     */
    @Config(qualifiers = "w411dp-h891dp")
    fun `the join between two neighbours makes a superset of them`() {
        setContent(populated())

        composeTestRule.onNodeWithContentDescription(
            "Superset. Perform Bench press and Cable fly back to back",
        ).performClick()

        assertEquals(listOf(TemplateEditorEvent.SupersetLinked(1L)), events)
    }

    @Test
    fun `the join under a pair that is already one breaks it`() {
        setContent(populated())

        scrollTo(
            hasContentDescription("Superset A. Stop performing Cable fly and Tricep pushdown back to back"),
        ).performClick()

        assertEquals(listOf(TemplateEditorEvent.SupersetUnlinked(2L)), events)
    }

    @Test
    fun `adding exercises records the picker is open and pushes it`() {
        setContent(populated())

        scrollTo(hasText("ADD EXERCISES")).performClick()

        assertEquals(listOf(TemplateEditorEvent.ExercisesPickRequested), events)
        // Not the single-choice picker: this is the control that adds, and a plan takes as many
        // exercises as the user ticks.
        assertEquals(listOf(false), pickerOpens)
    }

    @Test
    fun `the swap on a card says which slot asked and opens the picker for one`() {
        setContent(populated())

        // The action rather than a touch, as the × beside it: the control sits on the second card
        // of a scrolling page, and where it lands under a finger is the layout's business.
        scrollTo(hasContentDescription("Swap Cable fly for another exercise"))
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf(TemplateEditorEvent.ExerciseSwapRequested(2L)), events)
        // A slot holds one exercise, so the picker is drawn as a single choice.
        assertEquals(listOf(true), pickerOpens)
    }

    @Test
    fun `a plan of one exercise still offers the swap`() {
        // The × and the ⇄ are about what is *in* the slot, so neither depends on there being
        // another slot — unlike the hold, which a plan of one has nothing to reorder with.
        setContent(populated().copy(exercises = populated().exercises.take(1)))

        composeTestRule
            .onNodeWithContentDescription("Swap Bench press for another exercise")
            .assertIsDisplayed()
    }

    @Test
    fun `the slot controls go inert while a write is out`() {
        setContent(populated().copy(isSaving = true))

        composeTestRule
            .onNodeWithContentDescription("Swap Bench press for another exercise")
            .assertIsNotEnabled()
    }

    @Test
    fun `save is inert until something has changed`() {
        setContent(populated())

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `save is live on an edited plan and reports the write`() {
        setContent(populated().copy(isDirty = true))

        composeTestRule.onNodeWithText("Save").assertIsEnabled()
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(listOf(TemplateEditorEvent.SaveRequested), events)
    }

    @Test
    fun `a figure that cannot be planned keeps the save inert`() {
        setContent(
            populated().copy(
                isDirty = true,
                exercises = populated().exercises.mapIndexed { index, row ->
                    if (index != 0) return@mapIndexed row
                    row.copy(sets = row.sets.map { it.copy(reps = "0", errors = setOf(TemplateSetField.REPS)) })
                },
            ),
        )

        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `tapping the header opens the name dialog`() {
        setContent(populated())

        composeTestRule.onNodeWithContentDescription(
            "Push Day · Upper, 3 exercises, about 45 minutes, Strength",
        ).performClick()

        assertEquals(listOf(TemplateEditorEvent.NameEditRequested), events)
    }

    @Test
    fun `the name dialog edits the name and the category together`() {
        setContent(
            populated().copy(
                naming = TemplateNamingDraft(
                    name = "Push Day · Upper",
                    category = TemplateCategory.STRENGTH,
                ),
            ),
        )

        composeTestRule.onNodeWithText("Name and category").assertIsDisplayed()
        composeTestRule.onNodeWithText("HYPERTROPHY").performClick()

        assertEquals(
            listOf(TemplateEditorEvent.CategoryChanged(TemplateCategory.HYPERTROPHY)),
            events,
        )
    }

    @Test
    fun `a template edited down to nothing says why the save is inert`() {
        setContent(populated().copy(exercises = emptyList(), isDirty = true))

        composeTestRule.onNodeWithText("Nothing in this template").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `a template that is not there is a dead end with a way out`() {
        setContent(TemplateEditorUiState(isLoading = false, isMissing = true))

        composeTestRule.onNodeWithText("No such template").assertIsDisplayed()
        composeTestRule.onNodeWithText("BACK TO TEMPLATES").performClick()

        assertEquals(1, exited)
    }

    @Test
    fun `backing out of an edited plan asks before throwing it away`() {
        setContent(populated().copy(isDirty = true))

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf(TemplateEditorEvent.BackRequested), events)
    }

    @Test
    fun `backing out of an untouched plan just leaves`() {
        setContent(populated())

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(emptyList<TemplateEditorEvent>(), events)
        assertEquals(1, exited)
    }

    @Test
    fun `the discard dialog offers to keep editing`() {
        setContent(populated().copy(isDirty = true, confirmingDiscard = true))

        composeTestRule.onNodeWithText("Leave without saving?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Keep editing").performClick()

        assertEquals(listOf(TemplateEditorEvent.DiscardDismissed), events)
    }

    @Test
    fun `a template being built is titled as one and headed by a placeholder`() {
        setContent(TemplateEditorUiState(isNew = true, isLoading = false))

        composeTestRule.onNodeWithText("New template").assertIsDisplayed()
        // The header row is the only way back into the naming dialog, so it has to be a row
        // there is something to press rather than an empty one.
        composeTestRule.onNodeWithContentDescription("Untitled template", substring = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Nothing in this template").assertIsDisplayed()
    }

    @Test
    fun `an unnamed template's header opens the dialog that names it`() {
        setContent(TemplateEditorUiState(isNew = true, isLoading = false))

        composeTestRule.onNodeWithContentDescription("Untitled template", substring = true)
            .performClick()

        assertEquals(listOf(TemplateEditorEvent.NameEditRequested), events)
    }

    @Test
    fun `discarding a template being built says nothing was saved rather than nothing changed`() {
        setContent(
            TemplateEditorUiState(isNew = true, isLoading = false, isDirty = true, confirmingDiscard = true),
        )

        composeTestRule.onNodeWithText("Nothing has been saved yet", substring = true)
            .assertIsDisplayed()
    }

    /**
     * Brings [matcher]'s node into view before touching it.
     *
     * The plan is a scrolling list on a phone-sized viewport, so everything below the first card
     * is off the bottom of it — and a node that is not displayed cannot be clicked or asserted
     * on. `performScrollToNode` on the `LazyColumn` composes the item the node is in, and
     * `performScrollTo` on the node then brings it through every scrollable above it. Scrolling
     * first is what the user does, so it is what the test does.
     */
    private fun scrollTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        composeTestRule.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher)
        return composeTestRule.onNode(matcher).performScrollTo()
    }

    private fun setContent(state: TemplateEditorUiState) {
        composeTestRule.setContent {
            TitanTheme {
                TemplateEditorScreen(
                    state = state,
                    onEvent = events::add,
                    onExit = { exited++ },
                    onPickExercises = pickerOpens::add,
                )
            }
        }
    }

    /** A push day whose last two exercises are one superset. */
    private fun populated() =
        TemplateEditorUiState(
            name = "Push Day · Upper",
            category = TemplateCategory.STRENGTH,
            estimatedDuration = 45.minutes,
            isLoading = false,
            exercises = listOf(
                row(
                    key = 1L,
                    name = "Bench press",
                    sets = listOf(
                        set(11L, SetType.WARMUP, weight = "40", reps = "12"),
                        set(12L, weight = "80", reps = "8"),
                        set(13L, weight = "80", reps = "8"),
                        set(14L, SetType.DROP_SET, weight = "60", reps = "10"),
                    ),
                ),
                row(2L, "Cable fly", sets = plan(20L, 3), supersetLabel = "A1", isLinkedToNext = true),
                row(3L, "Tricep pushdown", sets = plan(30L, 3), supersetLabel = "A2"),
            ),
        )

    /** [count] plain sets, keyed from [firstKey], for a card whose plan is not the point. */
    private fun plan(
        firstKey: Long,
        count: Int,
    ) = List(count) { set(firstKey + it) }

    private fun set(
        key: Long,
        setType: SetType = SetType.NORMAL,
        weight: String = "",
        reps: String = "",
        errors: Set<TemplateSetField> = emptySet(),
    ) = TemplateSetRow(key = key, setType = setType, weight = weight, reps = reps, errors = errors)

    private fun row(
        key: Long,
        name: String,
        sets: List<TemplateSetRow> = emptyList(),
        supersetLabel: String? = null,
        isLinkedToNext: Boolean = false,
    ) = TemplateExerciseRow(
        key = key,
        exerciseId = key,
        exercise = Exercise(id = key, name = name),
        sets = sets,
        supersetGroup = if (supersetLabel != null) 0 else null,
        supersetLabel = supersetLabel,
        supersetGroupName = if (supersetLabel != null) "A" else null,
        isLinkedToNext = isLinkedToNext,
    )
}
