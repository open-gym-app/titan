package com.opengym.feature.history.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * An eyebrow over a wrapping row of chips.
 *
 * A [FlowRow] rather than `OpenGymFilterChipRow`, which scrolls sideways. Seventeen muscle
 * groups in a horizontal scroller is a control that hides most of its options behind a
 * gesture: the user cannot see what is on offer, cannot see what is already selected if it
 * scrolled off, and has to swipe a 32dp-tall strip to find out. Wrapped, the whole vocabulary
 * is on screen at once.
 *
 * It is also the shape that survives the taxonomy growing. A scrolling row gets no worse to
 * *look at* as chips are added and steadily worse to use; a wrap gets one line taller and no
 * harder. Both places this is used — the filter sheet and the new-exercise form — are places
 * the user has to see everything before choosing, which is why neither of them scrolls.
 */
@Composable
internal fun ChipSection(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        Eyebrow(text = label)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
            content = content,
        )
    }
}
