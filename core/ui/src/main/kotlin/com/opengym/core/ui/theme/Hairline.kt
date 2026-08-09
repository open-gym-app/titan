package com.opengym.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The rule.
 *
 * The wireframes separate things with a line rather than with elevation, everywhere and
 * without exception: a card has a border, rows inside it have a divider between them, every
 * bar is ruled off from what it sits over. That is a deliberate choice about the medium — a
 * 1dp shadow is invisible on a phone held at arm's length in a bright gym, and a hairline is
 * not — so the weight and the two shades are tokens rather than numbers picked per call site.
 */

/**
 * The weight of every border and divider in the app.
 *
 * Not `Dp.Hairline` (which is 0dp — a physical pixel, so it thins as density rises) and not
 * a value a call site chooses: two lines of different weights on one screen read as a mistake
 * rather than as a hierarchy. Shade, not weight, is what distinguishes them — see
 * [HAIRLINE_SOFT_ALPHA].
 */
val HAIRLINE_WIDTH: Dp = 1.dp

/**
 * The alpha of the softer of the two rules — the separator *inside* a card, as opposed to the
 * card's own border.
 *
 * The wireframes carry two hairline colours (`rule` and `rule-soft`) and Material 3 has one
 * slot for both. Rather than spend an extended colour scheme on a single value, the softer
 * weight is `outlineVariant` at half alpha, which is what the wireframes' two values are to
 * within a shade in each scheme.
 */
const val HAIRLINE_SOFT_ALPHA = 0.5f
