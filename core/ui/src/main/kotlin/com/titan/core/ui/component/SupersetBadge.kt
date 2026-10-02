package com.titan.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.titan.core.ui.R

/**
 * `SS A1` — which superset an exercise is in, and where it sits inside it.
 *
 * The counterpart of [SupersetLink]: the link is the gap where a group is made, and this is what
 * the exercises either side of it then wear. Three surfaces draw it over the same grouping — the
 * workout's cards, the same cards collapsed for a reorder, and the template editor — so it is one
 * component rather than three call sites agreeing to spell `SS %s` the same way.
 *
 * Always [BadgeTone.ACCENT]. A superset is the one structural fact about a workout that changes
 * how it is *performed*, and the accent is what the app spends on the thing that matters right
 * now; a neutral badge here would read as a label rather than as a pairing.
 *
 * @param label the marker for this exercise — `A1`, `A2`, `B1` — from
 *   [supersetLabels]. Not the group's letter alone: the digit is what says which of the pair the
 *   user is looking at, and on two cards drawn one above the other that is the whole point.
 */
@Composable
fun SupersetBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    TitanBadge(
        text = stringResource(R.string.superset_badge, label),
        tone = BadgeTone.ACCENT,
        modifier = modifier,
    )
}
