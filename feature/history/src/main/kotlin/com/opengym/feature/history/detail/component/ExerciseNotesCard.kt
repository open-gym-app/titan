package com.opengym.feature.history.detail.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R

/**
 * The user's own remarks on an exercise, read and written in the same place.
 *
 * `Exercise.notes` is deliberately not `Exercise.instructions`: the how-to is the bundled
 * dataset's and is the same for everybody, and this is "seat on 3, left side lags". They are
 * drawn as two sections for that reason, and only this one is editable — on every exercise,
 * including the seeded ones, because a remark about *your* gym's machine is yours whoever
 * wrote the exercise.
 *
 * ### Why it is not simply a text field
 *
 * A permanently open field on a screen the user is mostly *reading* turns a note into a form:
 * an outlined box with a caret in it, and a keyboard one stray tap away, above the
 * instructions they actually came for. Read mode is a line of prose with a pencil on it; the
 * field appears when asked for and closes when the write lands.
 *
 * The empty state is the same control, saying what it is for. An exercise with no note draws
 * a prompt rather than an empty card, which is the difference between "nothing here" and
 * "nothing here *yet*".
 */
@Composable
internal fun ExerciseNotesCard(
    notes: String?,
    draft: String?,
    isSaving: Boolean,
    onEdit: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        if (draft == null) {
            NotesReadRow(notes = notes, onEdit = onEdit)
        } else {
            NotesEditor(
                draft = draft,
                isSaving = isSaving,
                onDraftChange = onDraftChange,
                onSave = onSave,
                onCancel = onCancel,
            )
        }
    }
}

/**
 * The note as written, with a pencil.
 *
 * The whole row is the target, not just the icon: a 24dp glyph at the end of a card is a
 * worse thing to hit in a gym than the card itself, and the icon stays because it is what
 * says the row is editable at all.
 */
@Composable
private fun NotesReadRow(notes: String?, onEdit: () -> Unit) {
    val hasNote = !notes.isNullOrBlank()
    val action = if (hasNote) {
        stringResource(R.string.detail_notes_edit)
    } else {
        stringResource(R.string.detail_notes_add)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(OpenGymTheme.spacing.card),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = notes?.takeIf { hasNote } ?: stringResource(R.string.detail_notes_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasNote) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Outlined.Edit,
            // On the icon rather than the row, so the row announces the note and the control
            // announces what pressing it does — which is not the same for an empty note.
            contentDescription = action,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** The field, and the two ways out of it. */
@Composable
private fun NotesEditor(
    draft: String,
    isSaving: Boolean,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }

    // Opening the editor puts the caret in it. A field that takes a second tap to type into is
    // one the user stops reaching for, and the tap that opened it was already the ask.
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        modifier = Modifier.padding(OpenGymTheme.spacing.card),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
            label = { Text(stringResource(R.string.detail_notes_label)) },
            shape = MaterialTheme.shapes.small,
            enabled = !isSaving,
            maxLines = NOTES_MAX_LINES,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.detail_notes_cancel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Button(
                onClick = onSave,
                // Enabled on an empty field on purpose: clearing the box is how a note is
                // deleted, so a disabled save would leave no way to take one back.
                enabled = !isSaving,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.detail_notes_save),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** How far the field grows before it scrolls: enough for a gym's worth of quirks. */
private const val NOTES_MAX_LINES = 6

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Exercise notes", widthDp = 360)
@Composable
fun ExerciseNotesCardPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseNotesCatalogue()
    }
}

@Preview(name = "Exercise notes, dark", widthDp = 360)
@Composable
fun ExerciseNotesCardDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ExerciseNotesCatalogue()
    }
}

@Composable
private fun ExerciseNotesCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        ExerciseNotesCard(
            notes = "Seat on 3. Bar to the nipple line, pause. Left arm lags on the last set.",
            draft = null,
            isSaving = false,
            onEdit = {},
            onDraftChange = {},
            onSave = {},
            onCancel = {},
        )
        ExerciseNotesCard(
            notes = null,
            draft = null,
            isSaving = false,
            onEdit = {},
            onDraftChange = {},
            onSave = {},
            onCancel = {},
        )
        ExerciseNotesCard(
            notes = "Seat on 3.",
            draft = "Seat on 3. Elbows tucked",
            isSaving = false,
            onEdit = {},
            onDraftChange = {},
            onSave = {},
            onCancel = {},
        )
    }
}
