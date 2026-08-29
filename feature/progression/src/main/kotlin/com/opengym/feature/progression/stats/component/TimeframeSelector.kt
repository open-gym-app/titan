package com.opengym.feature.progression.stats.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.stats.StatsTimeframe

/**
 * The window selector: `7D · 30D · 90D · 1Y · ALL`, one of them taken.
 *
 * ### A segmented control, not the tab strip
 *
 * `OpenGymTabRow` is the app's tab strip — mono labels under an accent underline, ruled off —
 * and it means "these are different *pages*". This means "the same page, over a different
 * window", which is why the wireframes draw it as a recessed track with the taken segment
 * raised out of it. Using the tab strip here would say the five are five screens, and would
 * put a second accent underline on a screen whose accent is already spoken for by the volume
 * chart.
 *
 * ### The accent is not spent on it
 *
 * The taken segment is a card-coloured chip on a filled track, distinguished by *level* rather
 * than by hue. The accent on this screen marks the figures that moved — the change chip, the
 * peak week, the muscles worked directly — and a control that spent it would put the loudest
 * thing on the page on the least interesting decision on it.
 *
 * ### One radio group, five options
 *
 * `selectableGroup` with `Role.RadioButton` per segment, which is what makes TalkBack announce
 * "2 of 5, selected" rather than five unrelated buttons. Each carries the timeframe's full
 * name — `Last 90 days` — because `90D` read aloud is not a phrase, exactly as `PrBadge` says
 * the words its abbreviation stands for.
 */
@Composable
fun TimeframeSelector(
    selected: StatsTimeframe,
    onSelect: (StatsTimeframe) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.small,
            )
            .padding(TRACK_INSET)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(TRACK_INSET),
    ) {
        StatsTimeframe.entries.forEach { timeframe ->
            TimeframeSegment(
                timeframe = timeframe,
                isSelected = timeframe == selected,
                onSelect = { onSelect(timeframe) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One segment.
 *
 * The description rides on the same node the selection lives on rather than replacing it:
 * `clearAndSetSemantics` here would wipe the selectable role along with the label, and a radio
 * button a screen reader cannot tell is a radio button is a control that lied.
 */
@Composable
private fun TimeframeSegment(
    timeframe: StatsTimeframe,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container: Color = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        Color.Transparent
    }
    val content: Color = if (isSelected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.outline
    }
    val spoken = stringResource(timeframe.descriptionRes)

    Box(
        modifier = modifier
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .background(color = container, shape = MaterialTheme.shapes.extraSmall)
            .padding(vertical = OpenGymTheme.spacing.sm)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(timeframe.labelRes),
            style = OpenGymTheme.typography.numericSmall,
            // The taken segment is heavier as well as lighter-grounded: on a 360dp screen the
            // five chips are 60dp wide, and level alone is a difference somebody checking their
            // phone between sets can miss.
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * The gap between the track's edge and the chips inside it, and between two chips.
 *
 * Below the spacing scale's smallest step on purpose: it is the seam of a single control
 * rather than a gap between two elements, and at 4dp the five segments stop reading as one
 * track. The wireframes draw 3.
 */
private val TRACK_INSET = 3.dp
