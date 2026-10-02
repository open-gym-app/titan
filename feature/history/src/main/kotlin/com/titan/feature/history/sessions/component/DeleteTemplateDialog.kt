package com.titan.feature.history.sessions.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.titan.feature.history.R

/**
 * "Delete this template?"
 *
 * ### Why it asks, when nothing else in this tab does
 *
 * A template is a plan the user built rather than a row of data, and the delete cascades to its
 * exercises — so the undo an immediate delete would need is a whole template held in memory
 * until a snackbar times out. The confirmation costs one tap on an action taken once in the
 * life of a template.
 *
 * ### Why it says what is *not* lost
 *
 * The thing a lifter fears here is their history: they have run this plan eleven times, and
 * deleting it looks like deleting those workouts. It does not — `workout_session.template_id`
 * is `SET NULL`, so the sessions survive the plan they were run from — and the body says so,
 * because the alternative is a user who never deletes a template they have stopped using.
 *
 * @param name what is being deleted. In the question rather than under it: "delete this
 *   template?" is a different question depending on which one, and the user is looking at a
 *   list of six.
 * @param isDeleting the write is out. Both buttons go inert rather than the dialog closing, so
 *   a failure has somewhere to be reported.
 */
@Composable
internal fun DeleteTemplateDialog(
    name: String,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        title = { Text(text = stringResource(R.string.sessions_template_delete_title, name)) },
        text = {
            Text(
                text = stringResource(R.string.sessions_template_delete_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isDeleting) {
                Text(
                    text = stringResource(R.string.sessions_template_delete_confirm),
                    // The one destructive action on this tab, and the only place `error` is
                    // spent as a label colour — which is what keeps it from reading as an
                    // ordinary choice between two buttons.
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text(text = stringResource(R.string.sessions_template_delete_cancel))
            }
        },
    )
}
