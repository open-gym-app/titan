package com.opengym.core.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.OpenGymTheme

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
 * Shared rather than owned by the workout screen, because a workout can be thrown away from
 * **two** places — the session it is running in (#26) and the home screen's resume banner
 * (#23), which is where a session the app was left holding is usually met. One question, asked
 * the same way, with the same count in it: two copies is how the same irreversible action ends
 * up with a set count on one screen and a bare "are you sure" on the other.
 *
 * @param loggedSets how many sets go with it. Zero is the ordinary case — a session started by
 *   mistake, which is what this action exists for — and reads as a plain question with nothing
 *   to lose.
 * @param isDiscarding the delete is in flight. Holds both buttons: the confirm would be
 *   answered by a session that is already going, and dismissing over a delete in progress
 *   leaves the caller drawing a banner for a workout that is mid-deletion.
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
        title = { Text(text = stringResource(R.string.discard_session_title)) },
        text = {
            Text(
                text = if (loggedSets > 0) {
                    pluralStringResource(R.plurals.discard_session_body, loggedSets, loggedSets)
                } else {
                    stringResource(R.string.discard_session_body_empty)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isDiscarding) {
                Text(
                    text = stringResource(R.string.discard_session_confirm),
                    // The one destructive action in the flow, and the only place `error` is
                    // spent as a label colour — everything else here is the accent or a
                    // neutral, which is what keeps this from reading as an ordinary choice.
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDiscarding) {
                Text(text = stringResource(R.string.discard_session_cancel))
            }
        },
    )
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Discard - a workout with sets in it", widthDp = 360)
@Composable
fun DiscardSessionDialogPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        DiscardSessionDialog(loggedSets = 11, onConfirm = {}, onDismiss = {})
    }
}

@Preview(name = "Discard - nothing logged, dark", widthDp = 360)
@Composable
fun DiscardSessionDialogEmptyDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        // The everyday case: a workout opened by mistake, or one the process died under
        // before a set was entered.
        DiscardSessionDialog(loggedSets = 0, onConfirm = {}, onDismiss = {})
    }
}
