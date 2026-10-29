package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.ui.R
import com.titan.core.ui.theme.TitanTheme

/**
 * The join between one exercise and the one performed after it.
 *
 * Sits in the gap between two cards, and has two states: the exercises are performed back to
 * back — a superset — or they are not. Tapping it changes which.
 *
 * ### Why the control is a gap rather than a menu or a selection mode
 *
 * `Supersets` requires a group's members to occupy consecutive positions. A control that lives
 * *between two neighbours* can only ever ask for something legal, which is why there is no error
 * state here and no explaining to do: everything it can express is a superset. A three-exercise
 * group is two taps, on two gaps, and the domain absorbs the existing group each time — see
 * `LinkSupersetUseCase`.
 *
 * A drag *can* undo a superset — pulling one member away from the other is what "these are not
 * back to back any more" looks like, and both screens tear the group rather than bouncing the
 * drop (`ReorderSessionExercisesUseCase`, and [com.titan.core.domain.superset.Supersets.tear]
 * for a template's draft). This control is the way back, and the only way to make one in the
 * first place.
 *
 * ### Why the linked state is loud and the unlinked one is quiet
 *
 * A workout has one fewer gap than it has exercises, and most of them are not supersets. An
 * affordance drawn at full strength in every one of them would be a page of controls with a
 * workout somewhere behind it, so the offer is a hairline and a small label, and the *fact* — a
 * pair that is joined — is a filled pill on the accent, matching the [SupersetBadge]s on the two
 * cards it sits between.
 *
 * ### Why it is shared
 *
 * Two screens make supersets and they are the same control over the same rules: the workout
 * itself, and the template editor (#92), where the grouping being edited is the one a session
 * started from the template will be laid out with. A second copy is how the two end up
 * disagreeing about what a tap on the gap does.
 *
 * @param isLinked whether the two exercises either side are already one superset.
 * @param groupLabel the letter of the group they are in, for the pill to name when [isLinked].
 *   Null for a pair that is not joined, which has no group to name.
 * @param exerciseName the exercise above the join, and [nextExerciseName] the one below. Used
 *   only for the label read out to a screen reader, which is the one place "this join" has to be
 *   said in words — the visual is unambiguous because of where it is.
 */
@Composable
fun SupersetLink(
    isLinked: Boolean,
    exerciseName: String,
    nextExerciseName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    groupLabel: String? = null,
    enabled: Boolean = true,
) {
    // Named when it is one — `SUPERSET A`, matching the `SS A1` and `SS A2` badges on the cards
    // either side — and an offer when it is not.
    val label = when {
        isLinked && groupLabel != null -> stringResource(R.string.superset_linked_label, groupLabel)
        else -> stringResource(R.string.superset_link_label)
    }
    val action = stringResource(
        if (isLinked) R.string.superset_break else R.string.superset_link,
        exerciseName,
        nextExerciseName,
    )
    // The pill's own word first, then what a press does: "Superset A. Stop performing Bench
    // press and Cable fly back to back." A description that only said the action would be a
    // control whose spoken name has none of the text drawn on it — which Accessibility Scanner
    // flags, and which leaves a Voice Access user with nothing to say to reach it.
    val description = stringResource(R.string.superset_description, label, action)

    Column(
        modifier = modifier
            .fillMaxWidth()
            // One target for the whole thing, described once: the rules either side of the pill
            // are decoration, and a screen reader that announced them would announce nothing.
            //
            // The press is re-supplied here, on purpose. `clearAndSetSemantics` throws away
            // everything below it — the `Surface`'s click included — so a label alone would be
            // a node TalkBack can reach and cannot activate: the way into a superset, gone for
            // anyone driving the screen by screen reader. The same trap the rest dock's
            // countdown documents; the hand-off is the same shape.
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (enabled) onClick()
                    enabled
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        Connector(isLinked = isLinked)

        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(percent = 50),
            color = if (isLinked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = if (isLinked) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = TitanTheme.spacing.lg,
                    vertical = TitanTheme.spacing.sm,
                ),
                horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isLinked) Icons.Outlined.LinkOff else Icons.Outlined.Link,
                    contentDescription = null,
                    modifier = Modifier.width(ICON_SIZE).height(ICON_SIZE),
                )
                Text(
                    text = label,
                    style = TitanTheme.typography.eyebrow,
                    maxLines = 1,
                )
            }
        }

        Connector(isLinked = isLinked)
    }
}

/**
 * The short rule above and below the pill, which is what makes a linked pair read as one block
 * rather than as two cards with a chip between them.
 */
@Composable
private fun Connector(isLinked: Boolean) {
    Box(
        modifier = Modifier
            .width(CONNECTOR_WIDTH)
            .height(CONNECTOR_HEIGHT)
            .background(
                if (isLinked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            ),
    )
}

private val ICON_SIZE: Dp = 14.dp
private val CONNECTOR_WIDTH: Dp = 2.dp
private val CONNECTOR_HEIGHT: Dp = 8.dp
