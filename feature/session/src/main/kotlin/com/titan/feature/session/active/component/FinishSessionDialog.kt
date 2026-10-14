package com.titan.feature.session.active.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.session.R

/**
 * "End this workout?"
 *
 * END sits in the corner of the header bar, which is exactly where a thumb lands reaching for the
 * rest pill beside it or steadying the phone on a bench. Ending is not undoable — it stamps the
 * session closed and runs the whole progression cascade — so a stray tap used to be a workout cut
 * short with the remaining exercises unlogged. One question in front of it costs a tap once per
 * workout, which is the cheapest place in the app to spend one.
 *
 * Not asked over a workout with nothing ticked: `FinishSessionUseCase` refuses that one anyway,
 * and asking first only to be refused second is two interruptions for one answer. The ViewModel
 * skips the question there, and the refusal is drawn on the page.
 *
 * The figures are in the body so the question can be answered without closing it: the sets say
 * what is being saved, and the exercises with nothing logged are the usual sign the tap was a
 * mistake — a plan with two exercises still to go is rarely a plan someone meant to end.
 *
 * @param loggedSets the completed sets that will be saved. Always at least one — see above.
 * @param untouchedExercises exercises in the workout with nothing logged against them. Zero
 *   leaves the line out, rather than saying that nothing is missing.
 * @param canConfirm false while a set write is still in flight. The confirm waits for it rather
 *   than ending the session under it, for the reason `ActiveSessionUiState.canFinish` gives; the
 *   wait is a fraction of a second, so the button is simply inert for it.
 */
@Composable
fun FinishSessionDialog(
    loggedSets: Int,
    untouchedExercises: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    canConfirm: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        title = { Text(text = stringResource(R.string.finish_session_title)) },
        text = {
            // Two paragraphs rather than one joined string: how two sentences sit side by side
            // belongs to the locale, and a paragraph each needs no join at all.
            Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
                Text(
                    text = pluralStringResource(R.plurals.finish_session_sets, loggedSets, loggedSets),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (untouchedExercises > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.finish_session_untouched,
                            untouchedExercises,
                            untouchedExercises,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            // The accent rather than `error`: ending a workout is the outcome the screen exists
            // to reach, not a loss. What makes it deliberate is the question, not the colour.
            TextButton(onClick = onConfirm, enabled = canConfirm) {
                Text(text = stringResource(R.string.finish_session_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.finish_session_cancel))
            }
        },
    )
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Finish - exercises still to go", widthDp = 360)
@Composable
fun FinishSessionDialogPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        FinishSessionDialog(loggedSets = 11, untouchedExercises = 2, onConfirm = {}, onDismiss = {})
    }
}

@Preview(name = "Finish - everything logged, dark", widthDp = 360)
@Composable
fun FinishSessionDialogCompleteDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        FinishSessionDialog(loggedSets = 18, untouchedExercises = 0, onConfirm = {}, onDismiss = {})
    }
}
