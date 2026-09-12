package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * What to call this workout.
 *
 * ### One dialog, three moments
 *
 * A session is named before it starts, while it runs, and after it is over — the review panel,
 * the workout's header, and the recap, including for a session logged months ago. They are the
 * same question with the same rules, so they are one component: three dialogs would be three
 * places for "blank means unnamed" to be forgotten, and the user would meet a different field
 * depending on when they happened to think of a name.
 *
 * ### Blank is a valid answer
 *
 * Clearing the field and confirming is how a session goes back to being unnamed, which is why
 * the confirm button is never disabled. `workout_session.name` is nullable and `null` is not
 * `""` — null is what the date-derived fallback title keys off — and
 * `WorkoutSession.normalizeName` is what turns one into the other, on the domain side where
 * every writer shares it. Nothing here validates; there is nothing to validate.
 *
 * The field itself is [DialogNameField], shared with [TemplateNameDialog] — see it for why it is
 * not an `OutlinedTextField`.
 *
 * @param name what is in the field. Hoisted, and that is load-bearing for the workout: held in
 *   the caller's ViewModel it survives a rotation mid-rename, and held in a `remember` here it
 *   would not.
 * @param isSaving a write is in flight. The field stays up and inert rather than closing, so a
 *   failure has somewhere to be reported and the text is still there to retry with.
 * @param title what the dialog asks. Defaulted, and worth overriding only where "name this
 *   workout" would be the wrong tense.
 */
@Composable
fun SessionNameDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isSaving: Boolean = false,
    title: String = stringResource(R.string.session_name_title),
) {
    OpenGymDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        modifier = modifier,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg)) {
                DialogNameField(
                    name = name,
                    onNameChange = onNameChange,
                    onConfirm = onConfirm,
                    label = stringResource(R.string.session_name_label),
                    placeholder = stringResource(R.string.session_name_placeholder),
                    enabled = !isSaving,
                )
                Text(
                    text = stringResource(R.string.session_name_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            // Never disabled: an empty field is the answer "this session has no name", and a
            // button that went inert on it would leave no way back to unnamed.
            TextButton(onClick = onConfirm, enabled = !isSaving) {
                Text(text = stringResource(R.string.session_name_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(text = stringResource(R.string.session_name_cancel))
            }
        },
    )
}

/*
 * No `@Preview`s, matching every other dialog in the app.
 *
 * A `Dialog` composes into a window of its own, which is not the root Roborazzi captures — the
 * screenshot comes out as the empty page behind it, and the harness waits for an idle it never
 * sees. What this component looks like is covered by `SessionNameDialogTest` instead, which
 * drives it directly.
 */
