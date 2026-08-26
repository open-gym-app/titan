package com.opengym.core.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The body diagram.
 *
 * What a drawn illustration can be asserted on in a unit test is its *description*, and that
 * turns out to be the part worth asserting: the picture is covered by four screenshot
 * references, while the content description is the only thing a user of TalkBack gets and
 * nothing else checks it.
 *
 * The mapping from a muscle group to a drawn region is checked here too, because it is where
 * the seeded taxonomy and the illustration have to agree and neither one can tell when the
 * other moves.
 */
@RunWith(RobolectricTestRunner::class)
class BodyDiagramTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the description names the primary and secondary muscles separately`() {
        composeTestRule.setContent {
            OpenGymTheme {
                BodyDiagram(
                    primary = listOf(muscle("CHEST"), muscle("SHOULDERS")),
                    secondary = listOf(muscle("TRICEPS")),
                )
            }
        }

        composeTestRule
            .onNodeWithContentDescription(
                "Body diagram. Worked directly: Chest, Shoulders. Also worked: Triceps.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `a muscle worked by several exercises is named once, at its highest emphasis`() {
        // What a caller unioning a push day hands in: chest is the target three times over,
        // and the triceps that assist the bench are the point of the dips.
        composeTestRule.setContent {
            OpenGymTheme {
                BodyDiagram(
                    primary = listOf(muscle("CHEST"), muscle("TRICEPS"), muscle("CHEST")),
                    secondary = listOf(muscle("TRICEPS"), muscle("CHEST"), muscle("SHOULDERS")),
                )
            }
        }

        // Not "Chest, Triceps, Chest … Also worked: Triceps, Chest", which the picture —
        // which resolves the overlap at `secondary.toRegions() - primaryRegions` — denies.
        composeTestRule
            .onNodeWithContentDescription(
                "Body diagram. Worked directly: Chest, Triceps. Also worked: Shoulders.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `a diagram with nothing highlighted says so rather than staying silent`() {
        composeTestRule.setContent {
            OpenGymTheme { BodyDiagram(primary = emptyList()) }
        }

        composeTestRule
            .onNodeWithContentDescription("Body diagram. No muscles highlighted.")
            .assertIsDisplayed()
    }

    @Test
    fun `a muscle the illustration cannot draw is still announced`() {
        composeTestRule.setContent {
            OpenGymTheme { BodyDiagram(primary = listOf(muscle("REAR_DELTS"))) }
        }

        composeTestRule
            .onNodeWithContentDescription("Body diagram. Worked directly: Rear delts.")
            .assertIsDisplayed()
    }

    @Test
    fun `every seeded muscle group maps to a region`() {
        val unmapped = SEEDED_MUSCLE_GROUPS.filter { muscle(it).bodyRegion() == null }

        assertEquals(emptyList<String>(), unmapped)
    }

    @Test
    fun `the mapping is on the name, so a group with a different id still resolves`() {
        assertEquals(BodyRegion.LATS, MuscleGroup(id = 1, name = "LATS").bodyRegion())
        assertEquals(BodyRegion.LATS, MuscleGroup(id = 99, name = "LATS").bodyRegion())
    }

    @Test
    fun `negative - a group the seed does not know has no region`() {
        assertNull(muscle("REAR_DELTS").bodyRegion())
    }

    @Test
    fun `every region is drawn in at least one view`() {
        val undrawn = BodyRegion.entries.filter { region ->
            BodyView.entries.none { view -> region.isVisibleIn(view) }
        }

        assertEquals(emptyList<BodyRegion>(), undrawn)
    }

    @Test
    fun `a region declared visible in a view has a shape there, and none where it is not`() {
        BodyView.entries.forEach { view ->
            val drawn = bodyGeometry(view).regions.keys
            BodyRegion.entries.forEach { region ->
                assertEquals(
                    "$region in $view",
                    region.isVisibleIn(view),
                    region in drawn,
                )
            }
        }
    }

    @Test
    fun `a mirrored region is two shapes and a midline one is a single shape`() {
        val front = bodyGeometry(BodyView.FRONT).regions

        assertEquals(2, front.getValue(BodyRegion.CHEST).size)
        assertEquals(1, front.getValue(BodyRegion.ABDOMINALS).size)
    }

    @Test
    fun `a mirrored pair is reflected, not duplicated`() {
        val (left, right) = bodyGeometry(BodyView.FRONT).regions.getValue(BodyRegion.QUADRICEPS)

        val leftBounds = left.getBounds()
        val rightBounds = right.getBounds()

        assertTrue("the pair must not sit on top of each other", leftBounds.left < rightBounds.left)
        assertEquals(
            "the pair must be symmetric about the midline",
            BODY_WIDTH - leftBounds.right,
            rightBounds.left,
            TOLERANCE,
        )
    }

    private fun muscle(name: String) = MuscleGroup(id = name.hashCode().toLong(), name = name)

    private companion object {

        /** The taxonomy in `tools/seed/generate_seed.py`, restated so a drift is a red test. */
        val SEEDED_MUSCLE_GROUPS = listOf(
            "ABDOMINALS", "ABDUCTORS", "ADDUCTORS", "BICEPS", "CALVES", "CHEST",
            "FOREARMS", "GLUTES", "HAMSTRINGS", "LATS", "LOWER_BACK", "MIDDLE_BACK",
            "NECK", "QUADRICEPS", "SHOULDERS", "TRAPS", "TRICEPS",
        )

        /** Bezier bounds are computed in floating point; a fraction of a body unit is noise. */
        const val TOLERANCE = 0.01f
    }
}
