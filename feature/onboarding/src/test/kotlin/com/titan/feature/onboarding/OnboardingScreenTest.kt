package com.titan.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the flow draws for each step, and what it reports.
 *
 * The stateless half, driven with a hand-built [OnboardingUiState] — no ViewModel, no Hilt,
 * no store. Where the *flow* goes when an event is emitted is `OnboardingViewModelTest`'s
 * subject; this asks only that the right screen is on the glass and the right event leaves it.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<OnboardingEvent>()

    @Test
    fun `the welcome step says what the app is, and offers a way past it`() {
        setContent(OnboardingUiState())

        composeTestRule.onNodeWithText(WELCOME_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(STEP_ONE_OF_FIVE).assertIsDisplayed()
        composeTestRule.onNodeWithText(CONTINUE).assertIsDisplayed()
        composeTestRule.onNodeWithText(SKIP).assertIsDisplayed()
    }

    @Test
    fun `the primary action reports Continued`() {
        setContent(OnboardingUiState())

        composeTestRule.onNodeWithText(CONTINUE).performClick()

        assertEquals(listOf(OnboardingEvent.Continued), events)
    }

    @Test
    fun `the footer offers a way back, and reports BackPressed`() {
        setContent(OnboardingUiState(step = OnboardingStep.GOAL))

        // The system gesture does the same thing, but it is not an affordance: this is the
        // part a user can see, and losing it is how the flow came to look one-way.
        composeTestRule.onNodeWithText(BACK).performClick()

        assertEquals(listOf(OnboardingEvent.BackPressed), events)
    }

    @Test
    fun `the first step has nothing to go back to`() {
        setContent(OnboardingUiState())

        // Absent rather than disabled: there is nothing behind it, and a greyed control on the
        // very first screen of an app reads as something already broken.
        composeTestRule.onNodeWithText(BACK).assertDoesNotExist()
    }

    @Test
    fun `going back is inert while the flow is being recorded`() {
        setContent(OnboardingUiState(step = OnboardingStep.READY, isFinishing = true))

        composeTestRule.onNodeWithText(BACK).assertIsNotEnabled().performClick()

        assertEquals(emptyList<OnboardingEvent>(), events)
    }

    @Test
    fun `the header's skip reports Skipped`() {
        setContent(OnboardingUiState(step = OnboardingStep.GOAL))

        composeTestRule.onNodeWithText(SKIP).performClick()

        assertEquals(listOf(OnboardingEvent.Skipped), events)
    }

    @Test
    fun `the goal step shows the stored answer as the selected one`() {
        setContent(
            OnboardingUiState(
                step = OnboardingStep.GOAL,
                preferences = UserPreferences(trainingGoal = TrainingGoal.ENDURANCE),
            ),
        )

        composeTestRule.onNodeWithText(GOAL_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(ENDURANCE).assertIsSelected()
        composeTestRule.onNodeWithText(STRENGTH).assertIsNotSelected()
    }

    @Test
    fun `tapping an option reports the value it stands for`() {
        setContent(OnboardingUiState(step = OnboardingStep.GOAL))

        composeTestRule.onNodeWithText(HYPERTROPHY).performClick()

        assertEquals(
            listOf(OnboardingEvent.TrainingGoalSelected(TrainingGoal.HYPERTROPHY)),
            events,
        )
    }

    @Test
    fun `the level step shows the stored answer as the selected one`() {
        setContent(
            OnboardingUiState(
                step = OnboardingStep.LEVEL,
                preferences = UserPreferences(fitnessLevel = FitnessLevel.INTERMEDIATE),
            ),
        )

        composeTestRule.onNodeWithText(LEVEL_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(INTERMEDIATE).assertIsSelected()
        composeTestRule.onNodeWithText(BEGINNER).assertIsNotSelected()
        composeTestRule.onNodeWithText(ADVANCED).assertIsNotSelected()
    }

    @Test
    fun `tapping a level reports the value it stands for`() {
        setContent(OnboardingUiState(step = OnboardingStep.LEVEL))

        // The last option sits below the fold on Robolectric's 470dp device, and a tap that
        // lands off-screen is a tap on nothing — scrolled to first, as the recap rows are.
        composeTestRule.onNodeWithText(ADVANCED).performScrollTo().performClick()

        assertEquals(listOf(OnboardingEvent.FitnessLevelSelected(FitnessLevel.ADVANCED)), events)
    }

    @Test
    fun `tapping a unit system reports it`() {
        setContent(OnboardingUiState(step = OnboardingStep.UNITS))

        composeTestRule.onNodeWithText(UNITS_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(IMPERIAL).performClick()

        assertEquals(listOf(OnboardingEvent.UnitSystemSelected(UnitSystem.IMPERIAL)), events)
    }

    @Test
    fun `the closing step reads the answers back and has nothing left to skip`() {
        setContent(
            OnboardingUiState(
                step = OnboardingStep.READY,
                preferences = UserPreferences(
                    trainingGoal = TrainingGoal.HYPERTROPHY,
                    fitnessLevel = FitnessLevel.ADVANCED,
                    unitSystem = UnitSystem.IMPERIAL,
                ),
            ),
        )

        composeTestRule.onNodeWithText(READY_TITLE).assertIsDisplayed()
        // Each recap row announces as one node, so the answers are matched the way a screen
        // reader reads them rather than as four loose fragments. Scrolled to rather than
        // asserted where they lie: Robolectric's device is 470dp tall, shorter than any phone
        // this ships on, and the step scrolls there like every other screen in the app.
        composeTestRule.onNodeWithContentDescription(RECAP_GOAL)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(RECAP_LEVEL)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(RECAP_UNITS)
            .performScrollTo()
            .assertIsDisplayed()

        // The button says what it does rather than "Done", and the header's escape hatch is
        // gone: there is nothing left to skip past.
        composeTestRule.onNodeWithText(START).assertIsDisplayed()
        composeTestRule.onNodeWithText(SKIP).assertDoesNotExist()
    }

    @Test
    fun `the primary action is inert while the flow is being recorded`() {
        setContent(OnboardingUiState(step = OnboardingStep.READY, isFinishing = true))

        // Disabled rather than merely ignoring the press: a second one would fire a second
        // write and a second navigation at a screen that is already leaving.
        composeTestRule.onNodeWithText(START).assertIsNotEnabled().performClick()

        assertEquals(emptyList<OnboardingEvent>(), events)
    }

    private fun setContent(state: OnboardingUiState) {
        composeTestRule.setContent {
            TitanTheme {
                OnboardingScreen(state = state, onEvent = events::add)
            }
        }
    }

    private companion object {
        // The eyebrow uppercases in the composition's locale, so the counter and SKIP are
        // matched as they are drawn.
        const val STEP_ONE_OF_FIVE = "STEP 1 OF 5"
        const val SKIP = "SKIP"
        const val CONTINUE = "Continue"
        const val BACK = "Back"
        const val START = "Start training"

        const val WELCOME_TITLE = "Log the work. Watch it add up."
        const val GOAL_TITLE = "What are you training for?"
        const val LEVEL_TITLE = "How long have you been training?"
        const val UNITS_TITLE = "Kilos or pounds?"
        const val READY_TITLE = "You are set."

        const val STRENGTH = "Strength"
        const val HYPERTROPHY = "Hypertrophy"
        const val ENDURANCE = "Endurance"
        const val BEGINNER = "Beginner"
        const val INTERMEDIATE = "Intermediate"
        const val ADVANCED = "Advanced"
        const val IMPERIAL = "Imperial"

        const val RECAP_GOAL = "Training goal, Hypertrophy"
        const val RECAP_LEVEL = "Fitness level, Advanced"
        const val RECAP_UNITS = "Units, Imperial"
    }
}
