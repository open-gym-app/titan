package com.opengym.feature.history.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.ChipSection
import com.opengym.core.ui.component.OpenGymFilterChip
import com.opengym.core.ui.component.displayName
import com.opengym.core.ui.component.label
import com.opengym.core.ui.component.labelOrNull
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R

/**
 * The custom-exercise form, in a bottom sheet.
 *
 * A sheet rather than a screen because authoring an exercise is something you do *from*
 * somewhere else, on your way to something else — usually mid-workout, having found the
 * machine your gym has and the catalogue does not, or having spotted that you spelled it
 * wrong. Pushing a destination would take away the list, or the exercise, that the user is
 * looking at and put a back stack entry between them and what they were doing.
 *
 * The sheet is the wrapper only; [ExerciseForm] is the part with the layout in it, so a test
 * can drive the form without a sheet's animation and window behaviour in the way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseFormSheet(
    draft: ExerciseDraft,
    muscleGroups: List<MuscleGroup>,
    onDraftChange: (ExerciseDraft) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        // The form is taller than half a phone and every field matters, so there is no
        // half-expanded state worth stopping at on the way.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ExerciseForm(
            draft = draft,
            muscleGroups = muscleGroups,
            onDraftChange = onDraftChange,
            onSubmit = onSubmit,
            onCancel = onDismiss,
        )
    }
}

/**
 * Name, how it is measured, what it is done with, and what it works.
 *
 * One form for both jobs — see [ExerciseDraft]. Only two words differ between writing a new
 * exercise and correcting an existing one, the heading and the submit button, and both are
 * read off [ExerciseDraft.isNew] rather than passed in: a caller that could label an edit
 * "Create" is a caller that eventually does.
 *
 * Every control writes a whole [ExerciseDraft] back through [onDraftChange]. The form holds
 * no state of its own — including the text fields, which is deliberate: a field that
 * remembered its own contents would keep them across a failed save that the caller had
 * already re-emitted the draft for, and the two would then disagree about what the user typed.
 *
 * **The two muscle rows are one question asked twice**, because the domain distinguishes the
 * prime mover from the rest and volume attribution weights them differently. A single "muscles
 * worked" picker would force a guess, and the honest guess — everything is primary — credits
 * the triceps in full for every bench press.
 */
@Composable
fun ExerciseForm(
    draft: ExerciseDraft,
    muscleGroups: List<MuscleGroup>,
    onDraftChange: (ExerciseDraft) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OpenGymTheme.spacing.screen)
            .padding(bottom = OpenGymTheme.spacing.section),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        Text(
            text = if (draft.isNew) {
                stringResource(R.string.exercise_form_title_new)
            } else {
                stringResource(R.string.exercise_form_title_edit)
            },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.exercise_form_name)) },
            singleLine = true,
            isError = draft.isDuplicateName,
            supportingText = if (draft.isDuplicateName) {
                { Text(stringResource(R.string.exercise_form_name_duplicate)) }
            } else {
                null
            },
            shape = MaterialTheme.shapes.small,
            enabled = !draft.isSaving,
        )

        ChipSection(label = stringResource(R.string.exercise_form_type)) {
            ExerciseType.entries.forEach { type ->
                OpenGymFilterChip(
                    label = type.label(),
                    selected = type == draft.type,
                    onClick = { onDraftChange(draft.copy(type = type)) },
                )
            }
        }

        ChipSection(label = stringResource(R.string.exercise_form_equipment)) {
            EquipmentType.entries.forEach { equipment ->
                // OTHER has no label by design — see `EquipmentType.labelOrNull` — so it is
                // not offered. An exercise the user cannot name the kit for is one they can
                // leave on bodyweight.
                val label = equipment.labelOrNull() ?: return@forEach
                OpenGymFilterChip(
                    label = label,
                    selected = equipment == draft.equipment,
                    onClick = { onDraftChange(draft.copy(equipment = equipment)) },
                )
            }
        }

        ChipSection(label = stringResource(R.string.exercise_form_primary_muscle)) {
            muscleGroups.forEach { group ->
                OpenGymFilterChip(
                    label = group.displayName(),
                    selected = group.id == draft.primaryMuscleGroupId,
                    onClick = {
                        onDraftChange(
                            draft.copy(
                                primaryMuscleGroupId = group.id,
                                // A group cannot be both. Picking it as the prime mover takes
                                // it out of the secondaries rather than leaving a selection
                                // the save would silently drop.
                                secondaryMuscleGroupIds = draft.secondaryMuscleGroupIds - group.id,
                            ),
                        )
                    },
                )
            }
        }

        ChipSection(label = stringResource(R.string.exercise_form_secondary_muscles)) {
            muscleGroups.forEach { group ->
                if (group.id == draft.primaryMuscleGroupId) return@forEach
                val selected = group.id in draft.secondaryMuscleGroupIds
                OpenGymFilterChip(
                    label = group.displayName(),
                    selected = selected,
                    multiple = true,
                    onClick = {
                        val next = if (selected) {
                            draft.secondaryMuscleGroupIds - group.id
                        } else {
                            draft.secondaryMuscleGroupIds + group.id
                        }
                        onDraftChange(draft.copy(secondaryMuscleGroupIds = next))
                    },
                )
            }
        }

        OutlinedTextField(
            value = draft.notes,
            onValueChange = { onDraftChange(draft.copy(notes = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.exercise_form_notes)) },
            shape = MaterialTheme.shapes.small,
            enabled = !draft.isSaving,
            maxLines = NOTES_MAX_LINES,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        // Above the buttons rather than in a snackbar: this sheet covers the Scaffold that
        // hosts them, so a snackbar would play out behind it and leave the save button
        // quietly re-enabling as the only sign anything happened.
        if (draft.saveFailed) {
            Text(
                text = stringResource(R.string.exercise_form_save_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !draft.isSaving,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.exercise_form_cancel),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Button(
                onClick = onSubmit,
                enabled = draft.canSave,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (draft.isNew) {
                        stringResource(R.string.exercise_form_create)
                    } else {
                        stringResource(R.string.exercise_form_save)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** How far the notes field grows before it scrolls: enough for a gym's worth of quirks. */
private const val NOTES_MAX_LINES = 4

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private const val PREVIEW_CHEST_ID = 1L
private const val PREVIEW_TRICEPS_ID = 2L
private const val PREVIEW_SHOULDERS_ID = 3L

private val PREVIEW_GROUPS = listOf(
    MuscleGroup(id = PREVIEW_CHEST_ID, name = "CHEST"),
    MuscleGroup(id = PREVIEW_TRICEPS_ID, name = "TRICEPS"),
    MuscleGroup(id = PREVIEW_SHOULDERS_ID, name = "SHOULDERS"),
    MuscleGroup(id = 4, name = "LATS"),
    MuscleGroup(id = 5, name = "QUADRICEPS"),
)

@Preview(name = "Exercise form - new", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
fun ExerciseFormPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseForm(
            draft = ExerciseDraft(
                name = "Hammer Strength incline press",
                equipment = EquipmentType.MACHINE,
                primaryMuscleGroupId = PREVIEW_CHEST_ID,
                secondaryMuscleGroupIds = setOf(PREVIEW_TRICEPS_ID, PREVIEW_SHOULDERS_ID),
            ),
            muscleGroups = PREVIEW_GROUPS,
            onDraftChange = {},
            onSubmit = {},
            onCancel = {},
        )
    }
}

@Preview(name = "Exercise form - editing", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
fun ExerciseFormEditPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        // The one existing row: a heading that says "Edit exercise" and a button that says
        // "Save". Everything else about the form is identical, which is the point of it.
        ExerciseForm(
            draft = ExerciseDraft(
                id = 42,
                name = "Hammer Strength incline press",
                equipment = EquipmentType.MACHINE,
                primaryMuscleGroupId = PREVIEW_CHEST_ID,
                secondaryMuscleGroupIds = setOf(PREVIEW_TRICEPS_ID),
                notes = "Seat on 3. Left arm lags — pause at the top.",
            ),
            muscleGroups = PREVIEW_GROUPS,
            onDraftChange = {},
            onSubmit = {},
            onCancel = {},
        )
    }
}

@Preview(name = "Exercise form - duplicate name", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
fun ExerciseFormDuplicatePreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        // On `surface`, as `ExerciseFormSheet` draws it. `showBackground` is white whatever the
        // theme, and the dark scheme's `onSurface` is near-white — so without this the dark
        // reference is white text on white and catches no dark-theme regression at all.
        Surface(color = MaterialTheme.colorScheme.surface) {
            ExerciseForm(
                draft = ExerciseDraft(name = "Bench Press", isDuplicateName = true),
                muscleGroups = PREVIEW_GROUPS,
                onDraftChange = {},
                onSubmit = {},
                onCancel = {},
            )
        }
    }
}
