package com.titan.core.ui.component

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsMatcher.Companion.expectValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.titan.core.domain.model.LoadSuggestion
import com.titan.core.domain.model.PastPerformance
import com.titan.core.domain.model.SuggestionReason
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.ui.theme.LocalUnitSystem
import com.titan.core.ui.theme.MIN_TOUCH_TARGET
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The target line: what it says for each rule, what it says of itself to a screen reader, and
 * what a tap on it does — which is to ask, not to log.
 */
@RunWith(RobolectricTestRunner::class)
class SetTargetLineTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var fills = 0

    // ─── What it says ───────────────────────────────────────────────────────────────────

    @Test
    fun `an increase quotes the step, on the line and in the reason`() {
        setLine(target(weight = 82_500_000, reps = 3, reason = SuggestionReason.INCREASE, previousWeight = 80_000_000))

        composeTestRule
            .onNodeWithContentDescription(
                "Target 82.5 kg × 3 · +2.5 kg. Hit the top of the range last time — add 2.5 kg",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `an add-rep at the same weight shows no change`() {
        setLine(target(weight = 80_000_000, reps = 7, reason = SuggestionReason.ADD_REP, previousWeight = 80_000_000))

        // `+0 kg` would read as a step of nothing; the line is the figures alone.
        composeTestRule
            .onNodeWithContentDescription("Target 80 kg × 7. Inside the range last time — one more rep")
            .assertIsDisplayed()
    }

    @Test
    fun `a repeat asks for the top of the range again`() {
        setLine(target(weight = 80_000_000, reps = 6, reason = SuggestionReason.REPEAT, previousWeight = 80_000_000))

        composeTestRule
            .onNodeWithContentDescription(
                "Target 80 kg × 6. Hit the top of the range once — hit it again to earn the increase",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `a deload quotes what comes off, with the sign`() {
        setLine(target(weight = 72_000_000, reps = 3, reason = SuggestionReason.DELOAD, previousWeight = 80_000_000))

        composeTestRule
            .onNodeWithContentDescription("Target 72 kg × 3 · −8 kg. Fell short last time — take 8 kg off and rebuild")
            .assertIsDisplayed()
    }

    @Test
    fun `a bodyweight target is reps alone`() {
        setLine(target(weight = null, reps = 9, reason = SuggestionReason.ADD_REP, previousWeight = null))

        composeTestRule
            .onNodeWithContentDescription("Target 9 reps. Inside the range last time — one more rep")
            .assertIsDisplayed()
    }

    @Test
    fun `a bodyweight deload has nothing to take off, and says what to do instead`() {
        setLine(target(weight = null, reps = 8, reason = SuggestionReason.DELOAD, previousWeight = null))

        composeTestRule
            .onNodeWithContentDescription(
                "Target 8 reps. Fell short last time — start again at the bottom of the range",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `the figures are in the ambient unit`() {
        setLine(
            target(weight = 47_627_199, reps = 3, reason = SuggestionReason.INCREASE, previousWeight = 45_359_237),
            unitSystem = UnitSystem.IMPERIAL,
        )

        composeTestRule
            .onNodeWithContentDescription("Target 105 lb × 3 · +5 lb. Hit the top of the range last time — add 5 lb")
            .assertIsDisplayed()
    }

    // ─── What it is ─────────────────────────────────────────────────────────────────────

    @Test
    fun `it is one button, read once, with its action named`() {
        setLine(target(weight = 82_500_000, reps = 3, reason = SuggestionReason.INCREASE, previousWeight = 80_000_000))

        // One node for the whole line — the two texts are not separately reachable — with the
        // button role and a click label saying what a tap does, so a reader hears the target,
        // the reason and "double-tap to fill in the target" in that order.
        composeTestRule.onAllNodes(hasContentDescription("Target", substring = true)).assertCountEquals(1)
        composeTestRule.onAllNodes(hasText("Target", substring = true)).assertCountEquals(0)
        line()
            .assert(expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher("has the fill action") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label == "Fill in the target"
                },
            )
            .assertHeightIsAtLeast(MIN_TOUCH_TARGET)
    }

    @Test
    fun `tapping it asks for the fields to be filled`() {
        setLine(target(weight = 82_500_000, reps = 3, reason = SuggestionReason.INCREASE, previousWeight = 80_000_000))

        line().performClick()

        assertEquals(1, fills)
    }

    @Test
    fun `negative - disabled, it is still read and does nothing`() {
        setLine(
            target(weight = 82_500_000, reps = 3, reason = SuggestionReason.INCREASE, previousWeight = 80_000_000),
            enabled = false,
        )

        line().assertIsDisplayed().assertIsNotEnabled()
        line().performClick()

        assertEquals(0, fills)
    }

    // ─── Support ────────────────────────────────────────────────────────────────────────

    private fun line() = composeTestRule.onNodeWithContentDescription("Target", substring = true)

    private fun setLine(
        target: LoadSuggestion,
        unitSystem: UnitSystem = UnitSystem.METRIC,
        enabled: Boolean = true,
    ) {
        composeTestRule.setContent {
            TitanTheme {
                CompositionLocalProvider(LocalUnitSystem provides unitSystem) {
                    SetTargetLine(target = target, onFill = { fills++ }, enabled = enabled)
                }
            }
        }
    }

    private fun target(
        weight: Long?,
        reps: Int,
        reason: SuggestionReason,
        previousWeight: Long?,
    ) = LoadSuggestion(
        weight = weight,
        reps = reps,
        reason = reason,
        previousWeight = previousWeight,
        basis = PastPerformance(
            listOf(
                WorkoutSet(
                    sessionExerciseId = 1,
                    setNumber = 1,
                    reps = 6,
                    weight = previousWeight,
                    isCompleted = true,
                    completedAt = 0,
                    createdAt = 0,
                    updatedAt = 0,
                ),
            ),
        ),
    )
}
