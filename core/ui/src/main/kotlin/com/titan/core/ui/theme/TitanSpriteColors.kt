package com.titan.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The titan sprite's colours, in the app's own ink, paper and accent (canvas *02 Color tokens*).
 *
 * The sprite is the app's only pixel drawing, so it has no slot in the Material scheme. Its
 * accent pixels — the feet and the sparks — are the scheme's accent, so the titan never adds a
 * hue of its own.
 *
 * @property ink the outline.
 * @property head the face, the paper the eyes sit on.
 * @property headShade the face's shadow.
 * @property plate the dumbbell's plates.
 * @property plateLight the plates' highlight.
 * @property feet the feet, in the accent.
 * @property feetShadow the feet's shadow.
 * @property feetLight the feet's highlight.
 * @property spark sparks while exerting.
 * @property bar the barbell on the squat.
 * @property faint the props drawn in the faint ink: speed lines, the zz of sleep, the ghost's outline.
 * @property ghost the past self's body.
 * @property food what it eats.
 * @property water the pool.
 * @property waterLine the pool's surface.
 * @property ground the line it stands on.
 */
@Immutable
data class TitanSpriteColors(
    val ink: Color,
    val head: Color,
    val headShade: Color,
    val plate: Color,
    val plateLight: Color,
    val feet: Color,
    val feetShadow: Color,
    val feetLight: Color,
    val spark: Color,
    val bar: Color,
    val faint: Color,
    val ghost: Color,
    val food: Color,
    val water: Color,
    val waterLine: Color,
    val ground: Color,
)

internal val TitanLightSpriteColors = TitanSpriteColors(
    ink = Color(0xFF1A1A1A),
    head = Color(0xFFFAFAF7),
    headShade = Color(0xFFCFCBBF),
    plate = Color(0xFF3A3A3A),
    plateLight = Color(0xFF6A6A6A),
    feet = Color(0xFFCC4226),
    feetShadow = Color(0xFFA8341C),
    feetLight = Color(0xFFE0603F),
    spark = Color(0xFFCC4226),
    bar = Color(0xFF1A1A1A),
    faint = Color(0xFF6E6E6A),
    ghost = Color(0xFFFAFAF7),
    food = Color(0xFFD99A16),
    water = Color(0xFFDDEFE9),
    waterLine = Color(0xFF12503F),
    ground = Color(0xFFC8C8C8),
)

internal val TitanDarkSpriteColors = TitanSpriteColors(
    ink = Color(0xFF1A1A1A),
    head = Color(0xFFECECEA),
    headShade = Color(0xFFB4B4B0),
    plate = Color(0xFF55555C),
    plateLight = Color(0xFF85858C),
    feet = Color(0xFFFF6B3D),
    feetShadow = Color(0xFFC24A22),
    feetLight = Color(0xFFFF9A75),
    spark = Color(0xFFFF6B3D),
    bar = Color(0xFFECECEA),
    faint = Color(0xFF8A8A84),
    ghost = Color(0xFF101014),
    food = Color(0xFFF2C14E),
    water = Color(0xFF0F332A),
    waterLine = Color(0xFF6FD3B8),
    ground = Color(0xFF3A3A40),
)
