package com.titan.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.titan.app.navigation.TopLevelDestination
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The bottom bar under a large system font.
 *
 * A tab is a quarter of the screen wide, and `SESSIONS` in the eyebrow's tracked monospace stops
 * fitting it once the font is large enough. A label broken across two lines does not fit the bar,
 * and labels that each shrink to fit themselves leave `SESSIONS` smaller than `HOME` — a bar whose
 * tabs are set at different sizes looks like a mistake. They share one size, measured so the
 * widest fits.
 *
 * Native graphics, because under Robolectric's default mode every glyph is a pixel wide and
 * nothing ever overflows. Its font metrics are still not a phone's, so these tests drive the bar
 * at a scale far past anything a phone offers, which makes the widest label overflow here too and
 * so exercises the shrink rather than assuming it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TitanBottomBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `labels keep the eyebrow size wherever they already fit`() {
        setBar(fontScale = 1f)

        TopLevelDestination.entries.forEach { destination ->
            assertEquals(
                "${destination.name} was shrunk at the default font",
                EYEBROW_SIZE,
                sizeOf(label(destination)),
            )
        }
    }

    @Test
    fun `every label shares one size when the widest has to shrink`() {
        setBar(fontScale = HUGE_FONT_SCALE)

        val sizes = TopLevelDestination.entries.map { sizeOf(label(it)) }

        assertEquals("the labels were drawn at $sizes", 1, sizes.distinct().size)
        assertTrue(
            "the scale was meant to force a shrink, but the labels are still $sizes",
            sizes.first() < EYEBROW_SIZE,
        )
    }

    @Test
    fun `every label fits its tab once it has shrunk`() {
        setBar(fontScale = HUGE_FONT_SCALE)

        val tabWidth = composeTestRule.onRoot().fetchSemanticsNode().size.width /
            TopLevelDestination.entries.size

        TopLevelDestination.entries.forEach { destination ->
            val layout = layoutOf(label(destination))
            val textWidth = layout.getLineRight(0) - layout.getLineLeft(0)
            assertTrue(
                "${destination.name} is ${textWidth}px wide in a ${tabWidth}px tab",
                textWidth < tabWidth,
            )
        }
    }

    private fun setBar(fontScale: Float) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                TitanTheme {
                    TitanBottomBar(
                        destinations = TopLevelDestination.entries,
                        currentDestination = TopLevelDestination.HOME,
                        onNavigate = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun label(destination: TopLevelDestination): String =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getString(destination.labelRes)
            .uppercase()

    private fun sizeOf(text: String): TextUnit = layoutOf(text).layoutInput.style.fontSize

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val node = composeTestRule.onAllNodesWithText(text, useUnmergedTree = true)[0]
            .fetchSemanticsNode()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private companion object {
        /** `eyebrow`'s size in `Type.kt`. */
        val EYEBROW_SIZE = 10.sp

        /** Far past what a phone offers, so the widest label overflows even under JVM metrics. */
        const val HUGE_FONT_SCALE = 5f
    }
}
