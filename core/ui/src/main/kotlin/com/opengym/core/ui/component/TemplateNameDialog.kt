package com.opengym.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * What to call a template, and what it trains.
 *
 * ### One dialog, three moments
 *
 * A template is named when a finished workout is saved as one, renamed from the templates tab,
 * and retitled from the editor. Same question, same two rules — a name is required and has to be
 * unique — so it is one component, matching [SessionNameDialog]. Three copies would be three
 * places for the duplicate-name refusal to be reported differently.
 *
 * ### Why the name is asked for rather than taken
 *
 * A session is called "Push Day · Upper" *for the 28th of April*; a template is called that
 * forever, and sits in a list next to four others. The field is seeded from the session's name
 * where there is one, and then edited.
 *
 * ### Blank is not an answer here, unlike a session's name
 *
 * `CreateTemplateUseCase` refuses an empty name with `TemplateError.EmptyName`, because a
 * template with no name is unfindable in the list that is the only way to reach it. So the
 * confirm button goes inert on a blank field rather than committing to a refusal.
 *
 * ### The refusal is shown here, not in a snackbar
 *
 * [error] draws under the field, and the dialog stays up with what the user typed still in it.
 * A name already taken is the one failure the user can fix without retyping anything else, and a
 * message that appears at the bottom of the screen *after* the dialog has closed asks them to
 * start again to act on it.
 *
 * @param name what is in the field. Hoisted, like every other draft: the caller's ViewModel
 *   decides when the dialog closes, which is when the write lands and not before.
 * @param category the chosen category, or null. See [TemplateCategoryPicker] for why null is a
 *   chip rather than the absence of one.
 * @param body an optional line above the field, for a caller with something to say about what is
 *   being saved. "Save as template" on a screen full of figures is genuinely ambiguous about
 *   whether the weights come with it, and they do.
 * @param error the reason the last attempt was refused, already worded for the user, or null.
 * @param isSaving the write is in flight. Everything goes inert rather than the dialog closing,
 *   so a refusal has somewhere to appear.
 */
@Composable
fun TemplateNameDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String,
    confirmLabel: String,
    modifier: Modifier = Modifier,
    category: TemplateCategory? = null,
    onCategoryChange: (TemplateCategory?) -> Unit = {},
    body: String? = null,
    error: String? = null,
    isSaving: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    // A dialog that opens with the keyboard down asks for a second tap on the thing it just
    // opened to ask about.
    LaunchedEffect(Unit) { focus.requestFocus() }

    val canSave = name.isNotBlank() && !isSaving

    AlertDialog(
        // Gated, unlike the buttons, which say what they are by being disabled. A back press or
        // a tap outside reaches this whatever the buttons look like, and a dialog that closed
        // mid-write would re-arm the action behind it for a second, racing save.
        onDismissRequest = { if (!isSaving) onDismiss() },
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xxl)) {
                if (body != null) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md)) {
                    DialogNameField(
                        name = name,
                        onNameChange = onNameChange,
                        onConfirm = { if (canSave) onConfirm() },
                        label = stringResource(R.string.template_name_label),
                        placeholder = stringResource(R.string.template_name_placeholder),
                        modifier = Modifier.focusRequester(focus),
                        enabled = !isSaving,
                        error = error,
                    )
                    // Under the field it belongs to rather than under the whole dialog: the
                    // thing that was refused is the name, and the eye goes back to the field.
                    if (error != null) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                TemplateCategoryPicker(
                    selected = category,
                    onSelect = onCategoryChange,
                    enabled = !isSaving,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canSave) {
                Text(text = confirmLabel, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(
                    text = stringResource(R.string.template_name_cancel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}

/*
 * No `@Preview`s, matching every other dialog in the app — see the note on [SessionNameDialog].
 * `TemplateNameDialogTest` drives this one directly.
 */
