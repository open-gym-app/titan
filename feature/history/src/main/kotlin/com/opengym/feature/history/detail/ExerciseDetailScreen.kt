package com.opengym.feature.history.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.DifficultyLevel
import com.opengym.core.domain.model.Discipline
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MechanicType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.EmptyState
import com.opengym.core.ui.component.ExerciseHeroImage
import com.opengym.core.ui.component.MetaEntry
import com.opengym.core.ui.component.MetaGrid
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.component.label
import com.opengym.core.ui.component.labelOrNull
import com.opengym.core.ui.component.trackedLabel
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.detail.component.ExerciseNotesCard
import com.opengym.feature.history.detail.component.MusclesWorkedCard
import com.opengym.feature.history.exercise.ExerciseFormSheet
import kotlinx.coroutines.flow.filterNotNull

/**
 * One exercise, wired to its ViewModel.
 *
 * @param onBack leave the screen.
 * @param onAddToSession add this exercise to the workout being assembled, or **null** when
 *   there is no workout being assembled. Non-null exactly when the screen was reached from
 *   the exercise picker — see `exerciseDetailGraph` — because a button that says "add to
 *   session" in the Library tab has no session to add to.
 * @param onOpenPlateCalculator the shortcut into the plate calculator (#95).
 * @param onViewProgression the shortcut into this exercise's progression. **Null until M5's
 *   progression screens land**, which draws no button rather than one that goes nowhere.
 */
@Composable
fun ExerciseDetailRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToSession: (() -> Unit)? = null,
    onOpenPlateCalculator: (() -> Unit)? = null,
    onViewProgression: (() -> Unit)? = null,
    viewModel: ExerciseDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ExerciseDetailScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        onAddToSession = onAddToSession,
        onOpenPlateCalculator = onOpenPlateCalculator,
        onViewProgression = onViewProgression,
        modifier = modifier,
    )
}

/**
 * Everything the app knows about one exercise, and everything the user may write to it.
 *
 * ### What is on it, and in what order
 *
 * The photo, then what it works, then the facts, then the shortcuts, then the how-to, then
 * the user's own note. That order is the question being asked: somebody who opened this from
 * a list of hundreds is checking *is this the movement I mean*, which the picture and the muscle
 * map answer in a glance; somebody who has decided wants the cues, which are further down
 * where they will be read rather than scanned.
 *
 * ### Every section is optional, and an absent one is drawn as absent
 *
 * A user-authored exercise has no photo, no instructions, and possibly no muscle links. Each
 * of those sections is omitted entirely rather than drawn empty — a full-width image box with
 * a letter in it, or a heading over nothing, reads as a screen that failed to load rather
 * than as an exercise nobody has photographed. What is always there is the name, the tracked
 * fields, and the note, because those three exist for every row.
 *
 * ### The two kinds of writing are not the same control
 *
 * The **note** is the user's remark and is editable on any exercise, seeded or not. The
 * **edit** and **delete** actions in the bar rewrite or remove the exercise itself, and exist
 * only for a custom one: the seeded catalogue is a bundled dataset that a future release
 * replaces wholesale, so an edit to one of those would be silently undone. See
 * [ExerciseDetailUiState.canEdit].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(
    state: ExerciseDetailUiState,
    onEvent: (ExerciseDetailEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAddToSession: (() -> Unit)? = null,
    onOpenPlateCalculator: (() -> Unit)? = null,
    onViewProgression: (() -> Unit)? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.let { stringResource(it.textRes()) }

    // The message is marked shown *before* the snackbar is, and the effect is keyed on `Unit`
    // rather than on the message — the settings screen documents both halves of this: clearing
    // afterwards leaves the message for a recomposed screen to show twice, and re-keying on it
    // cancels the `showSnackbar` it is in the middle of.
    val currentMessage by rememberUpdatedState(message)
    LaunchedEffect(Unit) {
        snapshotFlow { currentMessage }
            .filterNotNull()
            .collect { text ->
                onEvent(ExerciseDetailEvent.MessageShown)
                snackbarHostState.showSnackbar(text)
            }
    }

    // Leaving is navigation, so it is the caller's; the screen only reports that the row it
    // was drawing is gone. Deliberately not driven by `exercise == null`, which is also true
    // for an id that never existed — see `ExerciseDetailUiState.isMissing`.
    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) onBack()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ExerciseDetailTopBar(
                title = state.exercise?.name.orEmpty(),
                canEdit = state.canEdit,
                onBack = onBack,
                onEdit = { onEvent(ExerciseDetailEvent.EditRequested) },
                onDelete = { onEvent(ExerciseDetailEvent.DeleteRequested) },
            )
        },
        bottomBar = {
            if (onAddToSession != null && state.exercise != null) {
                AddToSessionBar(onAddToSession = onAddToSession)
            }
        },
    ) { innerPadding ->
        val exercise = state.exercise

        when {
            // Nothing while the read is in flight. It is one indexed lookup, so a spinner is a
            // flash and the "no such exercise" page under it would be a lie for a frame.
            state.isLoading -> Unit

            state.isMissing -> EmptyState(
                glyph = stringResource(R.string.detail_glyph_missing),
                title = stringResource(R.string.detail_missing_title),
                description = stringResource(R.string.detail_missing_description),
                modifier = Modifier.padding(innerPadding),
            )

            // Gone because *this* screen deleted it: `onBack` is already in flight and the
            // screen is only still composed for the pop animation. Nothing, rather than the
            // "no longer exists" page flashing up on the way out.
            exercise == null -> Unit

            else -> ExerciseDetailBody(
                state = state,
                exercise = exercise,
                onEvent = onEvent,
                onOpenPlateCalculator = onOpenPlateCalculator,
                onViewProgression = onViewProgression,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    val editDraft = state.editDraft
    if (editDraft != null) {
        ExerciseFormSheet(
            draft = editDraft,
            muscleGroups = state.taxonomy,
            onDraftChange = { onEvent(ExerciseDetailEvent.EditChanged(it)) },
            onSubmit = { onEvent(ExerciseDetailEvent.EditSubmitted) },
            onDismiss = { onEvent(ExerciseDetailEvent.EditDismissed) },
        )
    }

    if (state.confirmingDelete) {
        DeleteExerciseDialog(
            name = state.exercise?.name.orEmpty(),
            isDeleting = state.isDeleting,
            onConfirm = { onEvent(ExerciseDetailEvent.DeleteConfirmed) },
            onDismiss = { onEvent(ExerciseDetailEvent.DeleteDismissed) },
        )
    }
}

@Composable
private fun ExerciseDetailBody(
    state: ExerciseDetailUiState,
    exercise: Exercise,
    onEvent: (ExerciseDetailEvent) -> Unit,
    onOpenPlateCalculator: (() -> Unit)?,
    onViewProgression: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        // Omitted rather than drawn as a fallback: `ExerciseHeroImage` would render the name's
        // initial across 240dp, which is a lot of screen spent saying there is no photo.
        if (exercise.icon != null) {
            ExerciseHeroImage(exercise = exercise)
        }

        if (state.muscleLinks.isNotEmpty()) {
            Section(title = stringResource(R.string.detail_section_muscles)) {
                MusclesWorkedCard(
                    primary = state.primaryMuscles,
                    secondary = state.secondaryMuscles,
                )
            }
        }

        MetaGrid(entries = exercise.metaEntries())

        ExerciseTools(
            onOpenPlateCalculator = onOpenPlateCalculator,
            onViewProgression = onViewProgression,
        )

        if (state.instructionSteps.isNotEmpty()) {
            Section(title = stringResource(R.string.detail_section_instructions)) {
                InstructionsCard(steps = state.instructionSteps)
            }
        }

        Section(title = stringResource(R.string.detail_section_notes)) {
            ExerciseNotesCard(
                notes = exercise.notes,
                draft = state.notesDraft,
                isSaving = state.isSavingNotes,
                onEdit = { onEvent(ExerciseDetailEvent.NoteEditRequested) },
                onDraftChange = { onEvent(ExerciseDetailEvent.NoteChanged(it)) },
                onSave = { onEvent(ExerciseDetailEvent.NoteSaved) },
                onCancel = { onEvent(ExerciseDetailEvent.NoteEditCancelled) },
            )
        }
    }
}

/** A [SectionHeading] and the block it introduces, at the gap the design gives that pair. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg)) {
        SectionHeading(text = title)
        content()
    }
}

/**
 * The facts, in the order a lifter asks for them.
 *
 * `listOfNotNull` rather than a fixed grid with blanks in it: the seed leaves the mechanic
 * unset on stretches and carries, and a user-authored exercise has only the last of these.
 * "Tracked" is always present because it is derived from a non-null column and is the one
 * fact that changes what the app will ask the user for.
 */
@Composable
private fun Exercise.metaEntries(): List<MetaEntry> = listOfNotNull(
    discipline?.let { MetaEntry(stringResource(R.string.detail_meta_discipline), it.label()) },
    equipmentType?.labelOrNull()
        ?.let { MetaEntry(stringResource(R.string.detail_meta_equipment), it) },
    mechanicType?.let { MetaEntry(stringResource(R.string.detail_meta_mechanic), it.label()) },
    difficultyLevel?.let { MetaEntry(stringResource(R.string.detail_meta_difficulty), it.label()) },
    MetaEntry(stringResource(R.string.detail_meta_tracked), type.trackedLabel()),
)

/**
 * The two jumps off this screen, drawn only where there is somewhere to jump to.
 *
 * The plate calculator is built; the progression screens are M5, so today this draws one button
 * across the full width. A disabled "soon" button beside it would be the settings screen's
 * convention — see #M4-23 — and it is the wrong one here: settings ships its whole information
 * architecture so the furniture does not move under people who have learned it, and a greyed-out
 * button in the middle of a reading screen is just something in the way.
 */
@Composable
private fun ExerciseTools(
    onOpenPlateCalculator: (() -> Unit)?,
    onViewProgression: (() -> Unit)?,
) {
    if (onOpenPlateCalculator == null && onViewProgression == null) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        if (onOpenPlateCalculator != null) {
            OutlinedButton(
                onClick = onOpenPlateCalculator,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.detail_plate_calculator),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (onViewProgression != null) {
            OutlinedButton(
                onClick = onViewProgression,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.detail_progression),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * The bundled how-to, numbered.
 *
 * The seed stores `instructions` as one step per line, so the numbers are the data's own
 * structure rather than decoration — and a wall of five sentences is exactly the thing nobody
 * reads standing at a machine. The figures are a numeric role, like every other number in the
 * app, which is also what keeps a two-digit step from shifting the column of text beside it.
 */
@Composable
private fun InstructionsCard(steps: List<String>) {
    OpenGymCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg)) {
                    Text(
                        text = "${index + 1}",
                        style = OpenGymTheme.typography.numericSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(STEP_NUMBER_WIDTH),
                    )
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The exercise's own bar: the way back, its name, and — for a custom one — the two ways to
 * change it.
 *
 * The screen sits outside the tab scaffold, so the shell has drawn nothing and this is the
 * only bar. Two icon buttons rather than an overflow menu: there are exactly two actions,
 * they are not going to become five, and a menu would hide a destructive action behind a
 * gesture as well as costing a tap for the other one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExerciseDetailTopBar(
    title: String,
    canEdit: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    // `titleMedium`, not `titleLarge`: seeded names run to "Barbell Incline
                    // Bench Press Medium-Grip", and a back arrow plus two actions leave about
                    // 190dp for it. Two lines of the smaller role beats one of the larger and
                    // an ellipsis in the middle of the word that distinguishes the movement.
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = TITLE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.detail_back),
                    )
                }
            },
            actions = {
                if (canEdit) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.detail_edit),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.detail_delete),
                            // The one destructive control on the screen, and the only thing on
                            // it drawn in the error role.
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        // The wireframes rule off every bar rather than floating it on elevation. A hairline
        // is legible in a gym; a 1dp shadow is not.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The picker's answer, as a bar rather than as the last thing in the scroll.
 *
 * Pinned because it is why the user is on this screen at all when it is drawn: they opened an
 * exercise from the picker to check it, and the decision they came to make should not be
 * three screens of instructions away.
 *
 * The navigation-bar inset is asked for rather than assumed: under the app shell it resolves
 * to nothing, because `OpenGymApp` pads the graph for the system bars and consumes them, and
 * it is the whole inset anywhere this is hosted without that. Asking is what makes the button
 * clear of the gesture handle in both cases and double-padded in neither.
 */
@Composable
private fun AddToSessionBar(onAddToSession: () -> Unit) {
    Column(modifier = Modifier.navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Button(
            onClick = onAddToSession,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.screen),
        ) {
            Text(
                text = stringResource(R.string.detail_add_to_session),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * The confirmation for the one destructive action on this screen.
 *
 * It names the exercise, because a dialog that says "delete this exercise?" is answerable
 * without reading it and this one should not be. It also says what the schema will and will
 * not allow: the foreign keys on `session_exercise` and `template_exercise` are `RESTRICT`,
 * so a movement with history behind it cannot be removed at all, and the dialog says so
 * rather than promising the history is safe and then refusing — the refusal itself comes back
 * as a message, see `ExerciseDetailViewModel.delete`.
 */
@Composable
private fun DeleteExerciseDialog(
    name: String,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_delete_title)) },
        text = { Text(stringResource(R.string.detail_delete_body, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isDeleting) {
                Text(
                    text = stringResource(R.string.detail_delete_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text(stringResource(R.string.detail_delete_cancel))
            }
        },
        shape = MaterialTheme.shapes.large,
    )
}

private fun ExerciseDetailMessage.textRes(): Int = when (this) {
    ExerciseDetailMessage.NOTES_SAVE_FAILED -> R.string.detail_notes_save_failed
    ExerciseDetailMessage.EXERCISE_UPDATED -> R.string.detail_exercise_updated
    ExerciseDetailMessage.EXERCISE_SAVE_FAILED -> R.string.detail_exercise_save_failed
    ExerciseDetailMessage.DELETE_FAILED -> R.string.detail_delete_failed
}

/** Wide enough for `12.`, which is more steps than the seed's longest how-to has. */
private val STEP_NUMBER_WIDTH = 20.dp

/** Two, for the reason [ExerciseDetailTopBar] gives. */
private const val TITLE_MAX_LINES = 2

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private fun previewMuscle(id: Long, name: String) = MuscleGroup(id = id, name = name)

private val PREVIEW_CHEST = previewMuscle(1, "CHEST")
private val PREVIEW_TRICEPS = previewMuscle(2, "TRICEPS")
private val PREVIEW_SHOULDERS = previewMuscle(3, "SHOULDERS")

private val PREVIEW_BENCH = Exercise(
    id = 1,
    name = "Barbell Bench Press",
    type = ExerciseType.STRENGTH,
    discipline = Discipline.POWERLIFTING,
    equipmentType = EquipmentType.BARBELL,
    mechanicType = MechanicType.COMPOUND,
    difficultyLevel = DifficultyLevel.INTERMEDIATE,
    notes = "Seat the scapula before unracking. Left side lags on the last set.",
    instructions = "Lie back on a flat bench holding a barbell in the rack above you.\n" +
        "Grip the bar slightly wider than shoulder width and unrack it.\n" +
        "Lower the bar to the middle of your chest, elbows tucked to about 45 degrees.\n" +
        "Press back to lockout, keeping your feet planted and your glutes on the bench.",
    // A path the preview cannot resolve — Coil finds no asset under Robolectric — which makes
    // this also the case that proves the banner falls back to the letter rather than to a gap.
    icon = "seed/images/Barbell_Bench_Press_Medium_Grip.webp",
)

private val PREVIEW_BENCH_STATE = ExerciseDetailUiState(
    exercise = PREVIEW_BENCH,
    muscleLinks = listOf(
        ExerciseMuscleGroupLink(PREVIEW_CHEST, isPrimary = true),
        ExerciseMuscleGroupLink(PREVIEW_SHOULDERS, isPrimary = false),
        ExerciseMuscleGroupLink(PREVIEW_TRICEPS, isPrimary = false),
    ),
    isLoading = false,
)

/** What the user authors: a name, how it is logged, and nothing else the seed would supply. */
private val PREVIEW_CUSTOM_STATE = ExerciseDetailUiState(
    exercise = Exercise(
        id = 2,
        name = "Hammer Strength incline press",
        equipmentType = EquipmentType.MACHINE,
        isCustom = true,
    ),
    muscleLinks = listOf(ExerciseMuscleGroupLink(PREVIEW_CHEST, isPrimary = true)),
    isLoading = false,
)

/** Tall enough to capture the whole screen; a preview clips at the device height otherwise. */
private const val FULL_SCREEN_HEIGHT_DP = 1600

@Preview(name = "Exercise detail", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ExerciseDetailScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseDetailScreen(state = PREVIEW_BENCH_STATE, onEvent = {}, onBack = {})
    }
}

@Preview(name = "Exercise detail - dark", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ExerciseDetailScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ExerciseDetailScreen(state = PREVIEW_BENCH_STATE, onEvent = {}, onBack = {})
    }
}

@Preview(name = "Exercise detail - from the picker", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ExerciseDetailScreenPickingPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        // The one shape with a pinned action at the bottom, with the plate calculator wired.
        ExerciseDetailScreen(
            state = PREVIEW_BENCH_STATE,
            onEvent = {},
            onBack = {},
            onAddToSession = {},
            onOpenPlateCalculator = {},
        )
    }
}

@Preview(name = "Exercise detail - custom, sparse", showBackground = true)
@Composable
fun ExerciseDetailScreenCustomPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        // No photo, no how-to, one muscle and no note: every optional section absent, and the
        // edit and delete actions present because it is the user's own.
        ExerciseDetailScreen(state = PREVIEW_CUSTOM_STATE, onEvent = {}, onBack = {})
    }
}

@Preview(name = "Exercise detail - editing the note", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ExerciseDetailScreenNoteEditPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseDetailScreen(
            state = PREVIEW_BENCH_STATE.copy(notesDraft = "Seat the scapula before unracking."),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview(name = "Exercise detail - deleting", showBackground = true)
@Composable
fun ExerciseDetailScreenDeletePreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseDetailScreen(
            state = PREVIEW_CUSTOM_STATE.copy(confirmingDelete = true),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview(name = "Exercise detail - no such exercise", showBackground = true)
@Composable
fun ExerciseDetailScreenMissingPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ExerciseDetailScreen(
            state = ExerciseDetailUiState(isLoading = false),
            onEvent = {},
            onBack = {},
        )
    }
}
