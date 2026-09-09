package com.opengym.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opengym.app.navigation.TopLevelDestination
import com.opengym.core.ui.test.OpenGymTestTags
import com.opengym.core.ui.theme.OpenGymTheme

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
fun OpenGymBottomBar(
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                destinations.forEach { destination ->
                    BottomBarItem(
                        destination = destination,
                        selected = destination == currentDestination,
                        onClick = { onNavigate(destination) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.BottomBarItem(
    destination: TopLevelDestination,
    selected: Boolean,
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
            // tag is not. See `OpenGymTestTags`.
            .testTag(OpenGymTestTags.bottomBarItem(destination.name))
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
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
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
            modifier = Modifier.padding(top = OpenGymTheme.spacing.md),
        )
        Text(
            text = label.uppercase(),
            style = OpenGymTheme.typography.eyebrow,
            color = contentColor,
            modifier = Modifier.padding(bottom = OpenGymTheme.spacing.md),
        )
    }
}
