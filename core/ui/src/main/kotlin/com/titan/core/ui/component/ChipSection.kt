package com.titan.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.titan.core.ui.theme.TitanTheme

/**
 * An eyebrow over a wrapping row of chips.
 *
 * A [FlowRow] rather than `TitanFilterChipRow`, which scrolls sideways. Seventeen muscle
 * groups in a horizontal scroller is a control that hides most of its options behind a
 * gesture: the user cannot see what is on offer, cannot see what is already selected if it
 * scrolled off, and has to swipe a 32dp-tall strip to find out. Wrapped, the whole vocabulary
 * is on screen at once.
 *
 * It is also the shape that survives the taxonomy growing. A scrolling row gets no worse to
 * *look at* as chips are added and steadily worse to use; a wrap gets one line taller and no
 * harder. Every place this is used — the library's filter sheet, the new-exercise form, and
 * [TemplateCategoryPicker] — is a place the user has to see everything before choosing, which is
 * why none of them scrolls.
 */
@Composable
fun ChipSection(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        Eyebrow(text = label)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
            // No vertical gap of its own: each chip is already 48dp tall around a 30dp pill
            // (see [TitanFilterChip]), so the lines are 18dp apart as they are, and a gap on
            // top of that would be the reservation counted twice.
            content = content,
        )
    }
}
