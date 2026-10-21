package com.titan.core.ui.format

import android.icu.text.NumberFormat
import kotlin.math.absoluteValue

/*
 * How much something moved, as the user reads it.
 *
 * The sixth quantity in CONTRIBUTING.md § Using the design system's table, and here for the
 * same reason as the other five: `"${(ratio * 100).toInt()}%"` puts the sign in the wrong
 * place in half a dozen locales, uses ASCII digits in a dozen more, and has no inverse. A
 * percentage is also the one quantity where the *symbol's* position is genuinely locale-bound
 * — Turkish writes `%18`, French writes `18 %` with a no-break space — which is what
 * [NumberFormat.getPercentInstance] answers and no format string does.
 */

/**
 * A signed ratio as an unsigned percentage: `0.184` becomes `18%`, and so does `-0.184`.
 *
 * **Unsigned on purpose.** Every caller draws the direction as a glyph or a word beside this —
 * see `ChangeChip` — and `↑ -18%` is an arrow and a sign disagreeing. A caller that wants the
 * sign in the text should say so in a string resource, where a translator can move it.
 *
 * Rounded to whole percent. A hero figure's change is read at a glance and `18.4%` is a
 * precision the underlying figure does not have: volume is summed over whole weeks that were
 * widened to fit the period, so the last decimal is an artefact of where the window was cut.
 */
fun formatPercentChange(ratio: Double): String =
    NumberFormat.getPercentInstance(formattingLocale())
        .apply { maximumFractionDigits = 0 }
        .format(ratio.absoluteValue)

/**
 * A decimal without its sign: `3.2` stays `3.2`, and `-0.42` becomes `0.4`.
 *
 * Both halves of the stats hub's `AVG / WEEK · 3.2 · +0.4 vs prev` go through this — the figure
 * itself, which is never negative, and the change under it, whose sign the caller supplies from
 * a string resource for the reason above. One function rather than two, because a magnitude
 * formatted one way and its comparison formatted another is how `3.2` ends up sitting over
 * `+0.40`.
 *
 * @param maxFractionDigits how precise to be. One decimal for a figure like days-per-week,
 *   which runs 0 to 7 and is read directly; whole numbers for a count.
 */
fun formatMagnitude(
    value: Double,
    maxFractionDigits: Int,
): String = formatNumber(value = value.absoluteValue, maxFractionDigits = maxFractionDigits)
