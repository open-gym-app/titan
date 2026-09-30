package com.titan.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing rhythm, reached through [TitanTheme.spacing].
 *
 * Six steps — 4 / 6 / 8 / 12 / 14 / 16 — which is what the wireframes actually use, rather
 * than the 8dp grid a design system reaches for by default. The layout is dense: a set row
 * carries a set number, a weight, a rep count and a tick, and on a 360dp screen an 8dp
 * minimum between them wastes the width that the numbers need.
 *
 * The three semantic values below are aliases onto the same scale, not extra steps. They
 * exist because `padding(TitanTheme.spacing.screen)` says why a screen has 16dp of
 * margin and `padding(16.dp)` does not — and because if that margin ever moves, it moves
 * in one place instead of in every screen that happened to pick the same number.
 */
@Immutable
data class TitanSpacing(
    /** Hairline gaps: between a label and the value directly under it. */
    val xs: Dp = 4.dp,

    /** Inside a chip, between an icon and its label. */
    val sm: Dp = 6.dp,

    /** The default gap between two related elements in a stack. */
    val md: Dp = 8.dp,

    /** Between a heading and the block it introduces. */
    val lg: Dp = 12.dp,

    /** Padding inside a card. */
    val xl: Dp = 14.dp,

    /** The screen margin, and the gap between two unrelated sections. */
    val xxl: Dp = 16.dp,
) {
    /** The left and right margin of every screen. */
    val screen: Dp get() = xxl

    /** The inset between a card's border and its content. */
    val card: Dp get() = xl

    /** The vertical gap between two sections of a screen. */
    val section: Dp get() = xxl
}
