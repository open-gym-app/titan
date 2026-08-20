package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
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
 * @param name what is in the field. Hoisted, and that is load-bearing for the workout: held in
 *   the caller's ViewModel it survives a rotation mid-rename, and held in a `remember` here it
 *   would not.
 * ### Why the field is a `BasicTextField`
 *
 * Material's `OutlinedTextField` does not settle inside an `AlertDialog`. The dialog sizes
 * itself from its content's *intrinsic* width and the outlined field's decoration reports one
 * that depends on the width it is given, so the two remeasure each other forever: Compose never
 * reaches idle, and under Robolectric the test runs out of heap rather than failing. It is the
 * same trade `SetLogRow` makes for a different reason — what a field is *for* is a keyboard, a
 * single line, a visible focus and a label a screen reader can read, and all of that survives
 * dropping the container.
 *
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
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg)) {
                NameField(
                    name = name,
                    onNameChange = onNameChange,
                    onConfirm = onConfirm,
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

/**
 * The field itself: a bordered box with a cursor in it.
 *
 * `fillMaxWidth` on the box rather than on the text field alone, so the dialog's width is
 * decided by its title and its buttons rather than by how long the session's name happens to
 * be — a rename dialog that grew as the user typed would be a dialog that moved its own
 * confirm button out from under their thumb.
 */
@Composable
private fun NameField(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    enabled: Boolean,
) {
    val shape = MaterialTheme.shapes.small
    val label = stringResource(R.string.session_name_label)

    BasicTextField(
        value = name,
        onValueChange = onNameChange,
        enabled = enabled,
        readOnly = !enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        // The keyboard's own confirm does what the button does. A one-field dialog where
        // "done" only dismisses the keyboard is one the user presses twice.
        keyboardActions = KeyboardActions(onDone = { onConfirm() }),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        decorationBox = { field ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                    .border(HAIRLINE_WIDTH, MaterialTheme.colorScheme.outlineVariant, shape)
                    .padding(horizontal = OpenGymTheme.spacing.lg, vertical = OpenGymTheme.spacing.xl),
            ) {
                if (name.isEmpty()) {
                    Text(
                        text = stringResource(R.string.session_name_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                field()
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
