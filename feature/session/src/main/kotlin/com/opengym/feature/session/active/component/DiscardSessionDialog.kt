package com.opengym.feature.session.active.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.opengym.feature.session.R

/**
 * "Throw this workout away?"
 *
 * A confirmation rather than an undo, which is the one decision worth explaining here. The
 * alternative — discard immediately, offer an undo snackbar — reads better on paper and is
 * wrong for this action: the delete cascades through `session_exercise` to `workout_set`, so
 * undoing it means holding a whole workout in memory until a snackbar times out, and the user
 * discarding a session is usually walking out of a gym with their phone about to go in a pocket.
 *
 * The count is in the question on purpose. "Discard this session?" is a different question when
 * the answer costs eleven logged sets, and the user is the only one who knows which of those
 * two they are being asked.
 *
 * @param loggedSets how many sets go with it. Zero is the ordinary case — a session started by
 *   mistake, which is what this action exists for — and reads as a plain question with nothing
 *   to lose.
 */
@Composable
fun DiscardSessionDialog(
    loggedSets: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isDiscarding: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        title = { Text(text = stringResource(R.string.active_discard_title)) },
        text = {
            Text(
                text = if (loggedSets > 0) {
                    pluralStringResource(R.plurals.active_discard_body, loggedSets, loggedSets)
                } else {
                    stringResource(R.string.active_discard_body_empty)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isDiscarding) {
                Text(
                    text = stringResource(R.string.active_discard_confirm),
                    // The one destructive action in the flow, and the only place `error` is
                    // spent as a label colour — everything else here is the accent or a
                    // neutral, which is what keeps this from reading as an ordinary choice.
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDiscarding) {
                Text(text = stringResource(R.string.active_discard_cancel))
            }
        },
    )
}
