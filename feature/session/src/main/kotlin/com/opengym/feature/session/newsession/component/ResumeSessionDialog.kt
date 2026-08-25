package com.opengym.feature.session.newsession.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.Dialog
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R
import com.opengym.feature.session.newsession.RunningWorkout

/**
 * The workout that is in the way, and the three things that can be done about it.
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
 * ### It shows the workout rather than describing it
 *
 * The head of the dialog is the session chrome — the same dark band, accent eyebrow and title
 * the workout itself runs under, in [OpenGymTheme.sessionChrome] — carrying that session's
 * name and what is logged in it. A user asked "do you want to keep this or bin it" needs to
 * know *which* workout and *what is in it*, and the app already has a way of drawing a running
 * session that they have been looking at all week. A paragraph explaining the same facts is
 * both longer and harder to answer from.
 *
 * The set count is the figure the question turns on, and it is drawn rather than described for
 * the reason `DiscardSessionDialog` gives about its own: "discard this workout" is a different
 * question when the answer costs eleven sets, and the user is the only one who knows which of
 * the two they are being asked.
 *
 * ### Three answers, laid out as three
 *
 * The workout in the way is one of two things — one somebody is in the middle of, or one they
 * have no intention of going back to. Resuming is the first. [onDiscard] is the second, and it
 * is the only exit that workout has: finishing is refused for a session with nothing logged, so
 * an abandoned empty workout cannot be closed any other way. Dismissing is the third and is a
 * real answer rather than a way of ducking the question — it leaves the plan on screen, and
 * START asks again. What it is *not* is a way to start the second session.
 *
 * All three are full-width and stacked. Side by side they do not fit across a 320dp dialog, and
 * right-aligned in a column they draw a ragged staircase whose widths mean nothing. Stacked and
 * flush, the differences left between them are the ones that carry: the accent on the one that
 * keeps the workout, the error container under the one that deletes it, and nothing at all
 * behind the one that changes nothing.
 *
 * @param isBusy a discard is in flight. Holds all three: the two that write would be answered
 *   by a session that is already gone, and dismissing over a delete in progress would leave the
 *   user on a plan whose blocker is mid-deletion.
 */
@Composable
fun ResumeSessionDialog(
    workout: RunningWorkout,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
) {
    Dialog(onDismissRequest = { if (!isBusy) onDismiss() }) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                RunningWorkoutBanner(workout)
                HairlineDivider()

                Column(
                    modifier = Modifier.padding(OpenGymTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                ) {
                    Text(
                        text = stringResource(R.string.new_session_resume_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onConfirm,
                        enabled = !isBusy,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.new_session_resume_confirm),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    Button(
                        onClick = onDiscard,
                        enabled = !isBusy,
                        shape = MaterialTheme.shapes.small,
                        // Tonal rather than the flat `error`, which would make deleting the
                        // workout the loudest thing on the screen and the accent above it the
                        // quieter option. What this has to be is unmistakable, not first.
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.new_session_resume_discard),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    TextButton(
                        onClick = onDismiss,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.new_session_resume_dismiss),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The running workout, drawn the way the workout screen draws itself.
 *
 * Its own chrome rather than the dialog's surface, which is the whole point of it: this band is
 * what a session in progress looks like everywhere else in the app, so it says "that workout"
 * before a word of it has been read.
 */
@Composable
private fun RunningWorkoutBanner(
    workout: RunningWorkout,
    modifier: Modifier = Modifier,
) {
    val chrome = OpenGymTheme.sessionChrome
    val logged = if (workout.loggedSets > 0) {
        pluralStringResource(R.plurals.new_session_resume_sets, workout.loggedSets, workout.loggedSets)
    } else {
        stringResource(R.string.new_session_resume_nothing_logged)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(chrome.surface)
            .padding(OpenGymTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
    ) {
        Eyebrow(text = stringResource(R.string.new_session_resume_eyebrow), color = chrome.accent)
        Text(
            // The same fallback the workout's own header uses, so a session started unnamed is
            // called the same thing here as on the screen this opens.
            text = workout.name?.takeIf { it.isNotBlank() } ?: formatSessionDate(workout.startedAt),
            style = MaterialTheme.typography.titleLarge,
            color = chrome.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Eyebrow(text = logged, color = chrome.onSurfaceVariant)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Workout already running", widthDp = 360)
@Composable
fun ResumeSessionDialogPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ResumeSessionDialog(
            workout = RunningWorkout(sessionId = 1L, name = "Push day", loggedSets = 11),
            onConfirm = {},
            onDiscard = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Workout already running, dark", widthDp = 360)
@Composable
fun ResumeSessionDialogDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        // Started unnamed and never logged into, which is the everyday case: a workout opened
        // by mistake, or one the process died under before a set was entered.
        ResumeSessionDialog(
            workout = RunningWorkout(sessionId = 1L, startedAt = 1_755_000_000_000L),
            onConfirm = {},
            onDiscard = {},
            onDismiss = {},
        )
    }
}
