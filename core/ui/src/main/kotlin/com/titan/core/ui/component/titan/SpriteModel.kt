package com.titan.core.ui.component.titan

/** Which colour of [com.titan.core.ui.theme.TitanSpriteColors] a sprite pixel takes. */
internal enum class SpriteInk {
    K,
    W,
    S,
    G,
    H,
    E,
    D,
    L,
    ACCENT,
    BAR,
    FAINT,
    GHOST,
    FOOD,
    WATER,
    WATER_LINE,
}

/**
 * The drawing's named groups. A pose moves, hides or fades whole groups, never single pixels,
 * which is what keeps eight poses one sprite.
 */
internal enum class SpritePart {
    ROOT,
    SHADOW,
    SPEED,
    RIVAL_ACC,
    RIVAL,
    ME,
    LEAN,
    LEG_L,
    LEG_R,
    UPPER,
    FX,
    CORE,
    BAR,
    HEAD,
    BROW,
    EYE,
    SMILE,
    MOUTH,
    HEADBAND,
    SHADES,
    CROWN,
    Z,
    Z1,
    Z2,
    FOOD_ACC,
    FOOD,
    BITE,
    SPARK_ACC,
    SPARK,
    SPLASH,
    SPLASH_1,
    SPLASH_2,
}

/** One rectangle of the sprite, in sprite pixels on its 64 x 40 grid. */
internal class SpritePixel(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val ink: SpriteInk,
)

/** A group of the drawing: its own pixels, drawn under its children, at [alpha]. */
internal class SpriteNode(
    val part: SpritePart,
    val alpha: Float,
    val pixels: List<SpritePixel>,
    val children: List<SpriteNode>,
)

internal fun node(
    part: SpritePart,
    alpha: Float = 1f,
    pixels: List<SpritePixel> = emptyList(),
    children: List<SpriteNode> = emptyList(),
): SpriteNode = SpriteNode(part, alpha, pixels, children)

internal fun px(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    ink: SpriteInk,
): SpritePixel = SpritePixel(x, y, width, height, ink)
