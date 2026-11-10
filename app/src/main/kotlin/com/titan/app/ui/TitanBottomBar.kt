package com.titan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titan.app.navigation.TopLevelDestination
import com.titan.core.ui.test.TitanTestTags
import com.titan.core.ui.theme.TitanTheme

/**
 * The bottom navigation bar.
 *
 * Hand-built rather than `NavigationBar`/`NavigationBarItem`, because Material's pill
 * indicator is the one piece of Material 3 this design cannot absorb: it puts a filled
 * lozenge behind the selected icon, which is a large block of accent colour sitting
 * permanently on screen. The accent has one job in this app — marking the thing that
 * matters right now — and spending it on "you are on the tab you are looking at" devalues
 * it everywhere else. The selected tab is marked the way the wireframes mark it: a 2dp rule
 * across the top of the item, and the accent on the icon and label.
 *
 * Accessibility is the reason for `selectable(role = Role.Tab)` rather than `clickable`:
 * it is what makes TalkBack announce "Sessions, tab, selected" instead of just the label,
 * and it is why the label is real text rather than a content description on the icon.
 */
@Composable
fun TitanBottomBar(
    destinations: List<TopLevelDestination>,
    currentDestination: TopLevelDestination?,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                val labelSize = fittingLabelSize(
                    labels = destinations.map { stringResource(it.labelRes).uppercase() },
                    available = maxWidth / destinations.size - LABEL_SIDE_PADDING * 2,
                    style = TitanTheme.typography.eyebrow,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    destinations.forEach { destination ->
                        BottomBarItem(
                            destination = destination,
                            selected = destination == currentDestination,
                            labelSize = labelSize,
                            onClick = { onNavigate(destination) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The one size every tab label is drawn at: the largest, no bigger than the eyebrow's own, at
 * which the *widest* of them still fits a tab with [LABEL_SIDE_PADDING] to spare on each side.
 *
 * One size for all four rather than each label shrinking to fit itself, because a bar whose
 * `SESSIONS` is set smaller than its `HOME` reads as a mistake. The size is found by measuring,
 * so it is the eyebrow's own 10sp wherever the labels already fit — every phone at the default
 * font, in every locale — and only comes down when a large font or a long translation leaves no
 * room, and never below [MIN_LABEL_SIZE].
 */
@Composable
private fun fittingLabelSize(
    labels: List<String>,
    available: Dp,
    style: TextStyle,
): TextUnit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val availablePx = with(density) { available.roundToPx() }

    return remember(labels, availablePx, style, density) {
        var size = style.fontSize.value
        while (size > MIN_LABEL_SIZE.value && labels.any { label ->
                measurer.measure(
                    text = label,
                    style = style.copy(fontSize = size.sp),
                    softWrap = false,
                    maxLines = 1,
                ).size.width > availablePx
            }
        ) {
            size -= LABEL_SIZE_STEP
        }
        size.sp
    }
}

@Composable
private fun RowScope.BottomBarItem(
    destination: TopLevelDestination,
    selected: Boolean,
    labelSize: TextUnit,
    onClick: () -> Unit,
) {
    val label = stringResource(destination.labelRes)
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .weight(1f)
            // The one handle `:benchmark` has on a tab: it drives a release-like build from
            // another process, where the label is whatever language the phone is in and the
            // tag is not. See `TitanTestTags`.
            .testTag(TitanTestTags.bottomBarItem(destination.name))
            // `selectable` and not `clickable`: the selected state is part of what a screen
            // reader must announce, and only this modifier carries it into the semantics
            // tree. `Role.Tab` is what makes it read as a tab rather than a button.
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            // The visible label is uppercase for the wireframes' technical look, and a
            // screen reader handed "SESSIONS" may spell it out letter by letter. Overriding
            // the merged node's description hands TalkBack the sentence-case word instead,
            // while `selectable` above keeps the role and the selected state.
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        // The selected marker. Always present so selecting a tab does not change the item's
        // height and nudge the labels; only its colour changes.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
        Icon(
            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
            // The label below says the same thing; describing the icon too makes TalkBack
            // read every tab twice.
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.padding(top = TitanTheme.spacing.md),
        )
        // One line, at the size [fittingLabelSize] chose for the whole bar.
        Text(
            text = label.uppercase(),
            style = TitanTheme.typography.eyebrow.copy(fontSize = labelSize),
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(bottom = TitanTheme.spacing.md),
        )
    }
}

/** The smallest a tab label is drawn, in sp; see [fittingLabelSize]. */
private val MIN_LABEL_SIZE = 6.sp

/** How much the label shrinks per step while it does not fit, in sp. */
private const val LABEL_SIZE_STEP = 0.5f

/** Clear space kept either side of a label, so two neighbours never touch. */
private val LABEL_SIDE_PADDING = 4.dp
