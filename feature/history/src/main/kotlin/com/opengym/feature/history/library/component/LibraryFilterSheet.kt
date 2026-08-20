package com.opengym.feature.history.library.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.ChipSection
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymFilterChip
import com.opengym.core.ui.component.displayName
import com.opengym.core.ui.component.labelOrNull
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.library.ExerciseLibraryEvent
import com.opengym.feature.history.library.ExerciseLibraryUiState
import com.opengym.feature.history.library.SEEDED_CATALOGUE_SIZE

/**
 * Every filter the library has, in a bottom sheet.
 *
 * A sheet rather than the two scrolling chip rows that used to sit at the top of the list.
 * Those cost 90dp of the list permanently to show about four of seventeen muscles, and the
 * other thirteen were behind a sideways swipe on a strip the user had first to scroll back up
 * to — so the common case paid for the control and the real case could not use it. Here the
 * list keeps its height, and the filters get a surface big enough to show all of them at once.
 *
 * Filters apply as they are tapped rather than on a confirm, so the button at the bottom says
 * how many exercises are left rather than "apply": it is live feedback that happens to also be
 * the way out, which is what lets somebody try a combination and see whether it was worth it
 * without closing the sheet.
 *
 * [LibraryFilterPanel] is the part with the layout in it, so a test can drive the filters
 * without a sheet's animation and window behaviour in the way — the same split
 * `ExerciseFormSheet` makes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryFilterSheet(
    state: ExerciseLibraryUiState,
    onEvent: (ExerciseLibraryEvent) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        // A half-expanded stop would cut the vocabulary in half, which is the one thing this
        // sheet exists not to do.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LibraryFilterPanel(state = state, onEvent = onEvent, onDone = onDismiss)
    }
}

/**
 * The two vocabularies, each under its own heading, each wrapped rather than scrolled.
 *
 * They look alike and behave differently on purpose. A muscle is a choice between
 * alternatives — "chest or quads" is not a question anybody asks — so the section has an ALL
 * chip and one selection. Equipment is a set, because "barbell or dumbbell" is a question
 * people ask constantly. The chips announce themselves accordingly; see `OpenGymFilterChip`.
 */
@Composable
internal fun LibraryFilterPanel(
    state: ExerciseLibraryUiState,
    onEvent: (ExerciseLibraryEvent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Scrollable because the taxonomy decides the height, not the design: seventeen
            // muscles wrap to four lines today and the seed is free to ship more.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OpenGymTheme.spacing.screen)
            .padding(bottom = OpenGymTheme.spacing.section),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.library_filters),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Absent rather than disabled when there is nothing to clear, for the reason
            // `OpenGymSearchField` hides its own: a permanently visible clear button that
            // does nothing reads as one that is broken.
            if (state.hasChipFilters) {
                TextButton(onClick = { onEvent(ExerciseLibraryEvent.FiltersReset) }) {
                    Eyebrow(
                        text = stringResource(R.string.library_filters_clear),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        ChipSection(label = stringResource(R.string.library_filter_muscle)) {
            OpenGymFilterChip(
                label = stringResource(R.string.library_filter_all),
                selected = state.selectedMuscleGroupId == null,
                onClick = { onEvent(ExerciseLibraryEvent.MuscleFilterSelected(null)) },
            )
            state.muscleGroups.forEach { group ->
                OpenGymFilterChip(
                    label = group.displayName(),
                    selected = group.id == state.selectedMuscleGroupId,
                    // Tapping the selected chip clears it, so ALL is a shortcut rather than
                    // the only way back to an unfiltered list.
                    onClick = {
                        val next = group.id.takeIf { it != state.selectedMuscleGroupId }
                        onEvent(ExerciseLibraryEvent.MuscleFilterSelected(next))
                    },
                )
            }
        }

        ChipSection(label = stringResource(R.string.library_filter_equipment)) {
            state.equipmentTypes.forEach { equipment ->
                // OTHER has no label by design — see `EquipmentType.labelOrNull` — so it never
                // gets a chip, even if the vocabulary offers one.
                val label = equipment.labelOrNull() ?: return@forEach
                OpenGymFilterChip(
                    label = label,
                    selected = equipment in state.selectedEquipment,
                    onClick = { onEvent(ExerciseLibraryEvent.EquipmentFilterToggled(equipment)) },
                    multiple = true,
                )
            }
        }

        Button(
            onClick = onDone,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.library_filters_done,
                    state.matchCount,
                    state.matchCount,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

private const val PREVIEW_CHEST_ID = 1L

private val PREVIEW_TAXONOMY = listOf(
    MuscleGroup(id = PREVIEW_CHEST_ID, name = "CHEST"),
    MuscleGroup(id = 2, name = "LATS"),
    MuscleGroup(id = 3, name = "TRICEPS"),
    MuscleGroup(id = 4, name = "BICEPS"),
    MuscleGroup(id = 5, name = "SHOULDERS"),
    MuscleGroup(id = 6, name = "QUADRICEPS"),
    MuscleGroup(id = 7, name = "HAMSTRINGS"),
    MuscleGroup(id = 8, name = "GLUTES"),
    MuscleGroup(id = 9, name = "CALVES"),
    MuscleGroup(id = 10, name = "ABDOMINALS"),
    MuscleGroup(id = 11, name = "LOWER BACK"),
    MuscleGroup(id = 12, name = "FOREARMS"),
)

private val PREVIEW_STATE = ExerciseLibraryUiState(
    muscleGroups = PREVIEW_TAXONOMY,
    equipmentTypes = listOf(
        EquipmentType.BARBELL,
        EquipmentType.DUMBBELL,
        EquipmentType.CABLE,
        EquipmentType.MACHINE,
        EquipmentType.BODYWEIGHT,
        EquipmentType.KETTLEBELL,
        EquipmentType.BAND,
    ),
    catalogueSize = SEEDED_CATALOGUE_SIZE,
    matchCount = SEEDED_CATALOGUE_SIZE,
    isLoading = false,
)

/**
 * On `surface`, which is what [LibraryFilterSheet] puts behind it.
 *
 * `showBackground` alone gives the preview a white backdrop whatever the theme, and the dark
 * scheme's `onSurface` is `#ECECEA` — so the dark reference rendered as near-white text on
 * white and could not have caught a dark-theme regression, which is the only thing it is for.
 */
@Composable
private fun FilterPanelPreview(state: ExerciseLibraryUiState) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        LibraryFilterPanel(state = state, onEvent = {}, onDone = {})
    }
}

@Preview(name = "Library filters", showBackground = true, widthDp = 360)
@Composable
fun LibraryFilterPanelPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        FilterPanelPreview(PREVIEW_STATE)
    }
}

@Preview(name = "Library filters - narrowed, dark", showBackground = true, widthDp = 360)
@Composable
fun LibraryFilterPanelNarrowedPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        FilterPanelPreview(
            PREVIEW_STATE.copy(
                selectedMuscleGroupId = PREVIEW_CHEST_ID,
                selectedEquipment = setOf(EquipmentType.BARBELL, EquipmentType.DUMBBELL),
                matchCount = 9,
            ),
        )
    }
}
