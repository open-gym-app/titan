/*
 * Pixel art, drawn block by block on the sprite's grid: its coordinates are the drawing, the way
 * `BodyShapes` records its anatomical ones, and naming each would only rename the picture.
 */
@file:Suppress("MagicNumber")

package com.titan.core.ui.component.titan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TitanActivity
import com.titan.core.domain.model.TitanPlace
import com.titan.core.ui.theme.TitanTheme

/**
 * The habitat drawn behind the titan: a backdrop, nothing more. Purely decorative, so it says
 * nothing to a screen reader.
 *
 * Drawn on the sprite's own pixel grid, sized the way [TitanSprite] sizes itself in the same
 * box, so the floor meets the sprite's ground line whatever the stage's width. Faint on purpose:
 * the titan stays the thing to look at, in either theme.
 */
@Composable
fun TitanHabitat(
    place: TitanPlace,
    modifier: Modifier = Modifier,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier) {
        val unit = pixelScale(minOf(size.width, size.height * SPRITE_RATIO), size.height).toFloat()
        val grid = Grid(this, unit, top = size.height - ROWS * unit)
        when (place) {
            TitanPlace.TRACK -> grid.track()
            TitanPlace.RACK -> grid.rack(ink)
            TitanPlace.POOL -> grid.pool()
            TitanPlace.RING -> grid.ring(ink)
            TitanPlace.SAUNA -> grid.sauna()
            TitanPlace.TRAIL -> grid.trail()
            TitanPlace.DOJO -> grid.dojo(ink)
            TitanPlace.SUMMIT -> grid.summit()
        }
    }
}

/** Sprite pixels on a canvas: [cols] across the whole width, [ROWS] down, ground at [GROUND]. */
private class Grid(
    val scope: DrawScope,
    val unit: Float,
    val top: Float,
) {
    val cols: Int = (scope.size.width / unit).toInt() + 1

    fun block(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: Color,
    ) = scope.drawRect(color, Offset(x * unit, top + y * unit), Size(w * unit, h * unit))

    /** Everything at and under the ground line, in [color]. */
    fun floor(color: Color) = block(0, GROUND, cols, ROWS - GROUND, color)
}

private fun Grid.track() {
    floor(TERRACOTTA.copy(alpha = SOFT))
    // Lane lines, and the stands far behind.
    for (x in 0 until cols step 6) block(x, GROUND + 2, 3, 1, LINE.copy(alpha = FAINT))
    for (x in 0 until cols step 4) block(x, 10, 3, 2 + (x / 4) % 2, STONE.copy(alpha = FAINT))
    block(0, 13, cols, 1, STONE.copy(alpha = FAINT))
}

private fun Grid.rack(ink: Color) {
    floor(STONE.copy(alpha = FAINT))
    // A rack on each side, with plates stacked on its pegs.
    listOf(3, cols - 9).forEach { x ->
        block(x, 8, 1, GROUND - 8, ink.copy(alpha = FAINT))
        block(x + 5, 8, 1, GROUND - 8, ink.copy(alpha = FAINT))
        block(x, 8, 6, 1, ink.copy(alpha = FAINT))
        block(x + 1, 18, 1, 5, TERRACOTTA.copy(alpha = SOFT))
        block(x + 4, 24, 1, 4, TERRACOTTA.copy(alpha = SOFT))
    }
    for (x in 0 until cols step 8) block(x, GROUND - 1, 4, 1, ink.copy(alpha = FAINT))
}

private fun Grid.pool() {
    block(0, GROUND, cols, ROWS - GROUND, WATER.copy(alpha = SOFT))
    // Lane ropes, and the tiles of the far wall.
    for (x in 0 until cols step 3) block(x, GROUND + 2, 2, 1, LINE.copy(alpha = SOFT))
    for (y in 6 until GROUND - 4 step 6) {
        for (x in (y / 6) % 2 * 3 until cols step 6) {
            block(x, y, 3, 3, WATER.copy(alpha = FAINT))
        }
    }
}

private fun Grid.ring(ink: Color) {
    floor(ink.copy(alpha = FAINT))
    // Three ropes behind, between two corner posts.
    listOf(14, 20, 26).forEach { y -> block(0, y, cols, 1, ROPE.copy(alpha = SOFT)) }
    block(2, 12, 2, GROUND - 12, ink.copy(alpha = SOFT))
    block(cols - 4, 12, 2, GROUND - 12, ink.copy(alpha = SOFT))
}

private fun Grid.sauna() {
    // Plank walls and benches, and a little steam.
    for (y in 4 until GROUND step 4) block(0, y, cols, 1, WOOD.copy(alpha = FAINT))
    floor(WOOD.copy(alpha = SOFT))
    block(0, GROUND - 7, cols, 2, WOOD.copy(alpha = SOFT))
    listOf(6, 12, cols - 10).forEach { x ->
        block(x, 6, 1, 2, LINE.copy(alpha = SOFT))
        block(x + 1, 4, 1, 2, LINE.copy(alpha = SOFT))
    }
}

private fun Grid.trail() {
    floor(LEAF.copy(alpha = SOFT))
    // Rolling hills, and a few trees.
    for (x in 0 until cols) {
        val hill = 4 + ((x * 3) % 17).let { if (it > 8) 17 - it else it } / 2
        block(x, GROUND - hill, 1, hill, LEAF.copy(alpha = FAINT))
    }
    listOf(5, 22, cols - 8).forEach { x ->
        block(x + 1, GROUND - 6, 1, 6, WOOD.copy(alpha = SOFT))
        block(x, GROUND - 12, 3, 6, LEAF.copy(alpha = SOFT))
    }
}

private fun Grid.dojo(ink: Color) {
    // Tatami underfoot, paper screens behind.
    floor(STRAW.copy(alpha = SOFT))
    for (x in 0 until cols step 10) block(x, GROUND, 1, ROWS - GROUND, ink.copy(alpha = FAINT))
    for (x in 0 until cols step 8) {
        block(x, 6, 1, GROUND - 6, ink.copy(alpha = FAINT))
        block(x, 6 + (GROUND - 6) / 2, 8, 1, ink.copy(alpha = FAINT))
    }
    block(0, 6, cols, 1, ink.copy(alpha = FAINT))
}

private fun Grid.summit() {
    floor(STONE.copy(alpha = SOFT))
    // Two peaks with snow on their tops.
    listOf(cols / 4 to 26, cols * 3 / 4 to 20).forEach { (peak, height) ->
        for (dx in -height..height) {
            val h = height - kotlin.math.abs(dx)
            if (h > 0) block(peak + dx, GROUND - h, 1, h, STONE.copy(alpha = FAINT))
            if (h > height - 4) block(peak + dx, GROUND - h, 1, 2, LINE.copy(alpha = SOFT))
        }
    }
}

private const val ROWS = 40
private const val GROUND = 35
private const val SPRITE_RATIO = 64f / 40f
private const val FAINT = 0.22f
private const val SOFT = 0.4f

private val TERRACOTTA = Color(0xFFC8553D)
private val STONE = Color(0xFF8A8F98)
private val WATER = Color(0xFF3B82C4)
private val ROPE = Color(0xFFD94841)
private val WOOD = Color(0xFF9A6A43)
private val LEAF = Color(0xFF4F8A4B)
private val STRAW = Color(0xFFC9A75E)
private val LINE = Color(0xFFE8EEF4)

@Preview(name = "Titan habitats", widthDp = 360, heightDp = 1000)
@Composable
fun TitanHabitatsPreview() {
    HabitatsPreview(ThemePreference.LIGHT)
}

@Preview(name = "Titan habitats, dark", widthDp = 360, heightDp = 1000)
@Composable
fun TitanHabitatsDarkPreview() {
    HabitatsPreview(ThemePreference.DARK)
}

@Composable
private fun HabitatsPreview(theme: ThemePreference) {
    TitanTheme(theme = theme, reducedMotion = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(TitanTheme.spacing.screen),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
            ) {
                TitanPlace.entries.forEach { place ->
                    Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                        TitanHabitat(place, Modifier.matchParentSize())
                        TitanSprite(
                            TitanActivity.IDLE,
                            head = null,
                            face = null,
                            contentDescription = null,
                            still = true,
                        )
                    }
                }
            }
        }
    }
}
