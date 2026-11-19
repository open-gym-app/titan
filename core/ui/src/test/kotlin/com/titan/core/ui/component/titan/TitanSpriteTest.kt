package com.titan.core.ui.component.titan

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.TitanActivity
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sprite's rules: whole pixels, sparks only while exerting, still frames that hold and differ,
 * and one node for a screen reader. What it looks like is the two screenshot references.
 */
@RunWith(RobolectricTestRunner::class)
class TitanSpriteTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ─── Whole pixels ───────────────────────────────────────────────────────────────────

    @Test
    fun `the sprite is never drawn at a fractional scale, and always fits`() {
        for (width in 1..2_000 step 7) {
            for (height in 1..1_200 step 11) {
                val scale = pixelScale(width.toFloat(), height.toFloat())
                assertTrue("scale $scale at $width x $height", scale >= 1)
                if (width >= 64 && height >= 40) {
                    assertTrue(scale * 64 <= width && scale * 40 <= height)
                    assertTrue(
                        "a larger whole scale fits $width x $height",
                        (scale + 1) * 64 > width || (scale + 1) * 40 > height,
                    )
                }
            }
        }
    }

    // ─── Sparks and props ───────────────────────────────────────────────────────────────

    @Test
    fun `sparks show while running and training, and nowhere else`() {
        TitanActivity.entries.forEach { activity ->
            val sparking = (0L until 4_000L step 37).any { millis ->
                val frame = TitanPoses.frame(activity, millis, still = false)
                SpritePart.FX !in frame.hidden && frame.alphas[SpritePart.CORE] != 0f
            }
            assertEquals("$activity", TitanPoses.sparks(activity), sparking)
        }
    }

    @Test
    fun `the barbell, the food, the water and the ghost belong to their own poses`() {
        fun shows(
            activity: TitanActivity,
            part: SpritePart,
        ) = part !in TitanPoses.frame(activity, 0, still = true).hidden

        assertEquals(listOf(TitanActivity.TRAIN), TitanActivity.entries.filter { shows(it, SpritePart.BAR) })
        assertEquals(listOf(TitanActivity.EAT), TitanActivity.entries.filter { shows(it, SpritePart.FOOD_ACC) })
        assertEquals(emptyList<TitanActivity>(), TitanActivity.entries.filter { shows(it, SpritePart.RIVAL_ACC) })
        assertEquals(
            listOf(TitanActivity.SWIM),
            TitanActivity.entries.filter { TitanPoses.frame(it, 0, true).showWater },
        )
    }

    // ─── Motion ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `every pose moves within its slowest loop`() {
        TitanActivity.entries.forEach { activity ->
            val frames = (
                0L until TitanPoses.loopMillis(
                    activity,
                ) step 10
                ).map { TitanPoses.frame(activity, it, still = false) }
            assertTrue("$activity never changes", frames.toSet().size > 1)
        }
    }

    @Test
    fun `with reduced motion no pose animates, and each still frame is its own`() {
        val stills = TitanActivity.entries.associateWith { activity ->
            val frames = (0L until 10_000L step 53).map { TitanPoses.frame(activity, it, still = true) }.toSet()
            assertEquals("$activity moved while still", 1, frames.size)
            frames.single()
        }

        stills.values.toList().let { all ->
            all.indices.forEach { i ->
                (i + 1 until all.size).forEach { j -> assertNotEquals("poses $i and $j look the same", all[i], all[j]) }
            }
        }
    }

    @Test
    fun `resting shuts the eyes and nothing else does, apart from a blink`() {
        assertEquals(0.5f, TitanPoses.frame(TitanActivity.REST, 0, still = true).eyeScale)
        TitanActivity.entries.filter { it != TitanActivity.REST }.forEach {
            assertEquals(1f, TitanPoses.frame(it, 0, still = true).eyeScale)
        }
        assertEquals(0.5f, TitanPoses.frame(TitanActivity.IDLE, 3_800, still = false).eyeScale)
    }

    // ─── The drawing ────────────────────────────────────────────────────────────────────

    @Test
    fun `the drawing is the canvas's, on its grid`() {
        fun count(node: SpriteNode): Int = node.pixels.size + node.children.sumOf(::count)
        fun parts(node: SpriteNode): List<SpritePart> = listOf(node.part) + node.children.flatMap(::parts)

        assertEquals(342, count(TITAN_SPRITE))
        val drawn = parts(TITAN_SPRITE).toSet()
        assertTrue("a part the poses move is missing", SpritePart.entries.all { it in drawn })
    }

    // ─── On screen ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the sprite is one node carrying the description it is given`() {
        composeTestRule.setContent {
            TitanTheme(reducedMotion = true) {
                TitanSprite(
                    TitanActivity.RUN,
                    head = "headband",
                    face = "shades",
                    contentDescription = "Brick, on a run",
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Brick, on a run")
            .assert(SemanticsMatcher("has no children") { it.children.isEmpty() })
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))
    }

    @Test
    fun `gear and a pose this build cannot draw render without a throw`() {
        composeTestRule.setContent {
            TitanTheme {
                TitanSprite(
                    TitanActivity.TRAIN,
                    head = "jetpack",
                    face = "monocle",
                    contentDescription = "x",
                    modifier = Modifier.size(256.dp, 160.dp),
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(3_000)

        composeTestRule.onNodeWithContentDescription("x").assert(SemanticsMatcher("exists") { true })
    }
}
