package com.opengym.feature.session.newsession.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.feature.session.R

/**
 * "There is already a workout running."
 *
 * The answer to the one refusal on this screen that is not the user's mistake:
 * `StartSessionUseCase` turns down a second session while one is in progress, and the session
 * blocking it is usually one the app itself left behind — a workout the process died under, or
 * one walked away from. Without this the refusal is a dead end with no way out of it: the
 * running session is not in the history list, which shows only what has been finished, and the
 * workout screen is reachable only by starting one.
 *
 * So the refusal offers the way back rather than reporting the problem. `SessionError`
 * carries the id of the session in the way — that is what it is for — and the workout screen
 * picks it up with `ResumeSessionUseCase`, which is the transition that exists for this.
 *
 * A dialog rather than the snackbar this used to be, because it asks something. A message that
 * fades leaves the user where they were, holding a plan they cannot start, with the sentence
 * that explained why already gone.
 *
 * Dismissing is a real answer and not a way of ducking the question: it leaves the plan on
 * screen, and START asks again. What it is *not* is a way to start the second session.
 */
@Composable
fun ResumeSessionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        title = { Text(text = stringResource(R.string.new_session_resume_title)) },
        text = {
            Text(
                text = stringResource(R.string.new_session_resume_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(R.string.new_session_resume_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.new_session_resume_dismiss))
            }
        },
    )
}
