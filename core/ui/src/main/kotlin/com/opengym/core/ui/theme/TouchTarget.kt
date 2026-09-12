package com.opengym.core.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The tap.
 *
 * Material's 48dp minimum, which Accessibility Scanner reports as an *error* below and which
 * every generated preview test now holds the app to — see `:core:testing`. Stated once, as a
 * token, because what a control has to *reserve* and what it *draws* are different numbers
 * and the design system draws many of its controls smaller than a thumb: a 32dp tick, a
 * 30dp filter chip, a 36dp square on the workout bar. Each of those keeps its size; what each
 * gains is 48dp of layout around it that nothing else may claim.
 *
 * ### Why the reservation has to be layout, not a modifier
 *
 * Compose already reports a clickable's touch bounds expanded to 48dp — but only into space no
 * other element has claimed, and siblings are settled from last to first. A 32dp control four
 * dp from its neighbour is announced as 36dp wide, whatever modifier it carries, because the
 * neighbour got there first. The fix is therefore always the same shape: a gap, a padding or a
 * minimum size that makes the room real. `Modifier.minimumInteractiveComponentSize()` is the
 * Material form of the same reservation and is what the chips use; the tables, whose grids
 * are sized to a 320dp screen, spend the room as gaps instead so the figures keep their width.
 */

/**
 * The smallest square a control may be announced as, and the floor under every row, chip,
 * cell and pill a thumb is meant to land on.
 */
val MIN_TOUCH_TARGET: Dp = 48.dp
