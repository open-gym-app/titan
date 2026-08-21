package com.opengym.feature.history.library.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.library.ExerciseLibraryUiState

/*
 * The library's own chrome, in the two shapes the screen is used in.
 *
 * As a tab the shell has already drawn a bar saying "Library" with a settings gear in it, so
 * the screen adds only what the shell cannot know: how much is in there and the way to add to
 * it — see [LibraryHeader]. As a picker there is no shell, and it draws the lot.
 */

/**
 * The tab's header: what the library holds, and the way to add to it.
 *
 * The one thing on this screen that does not scroll. One row, rather than the 130dp that
 * pinning the search box and both chip rows would cost under a bar and over another one, and
 * it carries the two things worth that much: the count, which is how a filtered search says
 * whether it did anything, and `+ NEW`, which is what a user comes to this tab to do and
 * should not have to scroll back to. Getting back to the search box and the filters is what
 * re-tapping the Library tab does.
 */
@Composable
internal fun LibraryHeader(
    state: ExerciseLibraryUiState,
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    onOpenFilters: () -> Unit,
    onNewExercise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = OpenGymTheme.spacing.screen, end = OpenGymTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Eyebrow(text = state.subtitle(selecting = false))
            LibraryActions(
                searchExpanded = searchExpanded,
                hasChipFilters = state.hasChipFilters,
                onToggleSearch = onToggleSearch,
                onOpenFilters = onOpenFilters,
                onNewExercise = onNewExercise,
            )
        }
        // The wireframes rule off every bar rather than floating it on elevation. A hairline
        // is legible in a gym; a 1dp shadow is not.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Search, filters, and adding to the library — the same three wherever the bar is drawn.
 *
 * Two icons and a word, rather than the search box and two chip rows that used to be the
 * first 130dp of the list. The controls a browsing screen needs are not the controls it should
 * spend its height on: most of the time the user is reading rows, and the moment they are not,
 * one tap is the whole cost of getting either control back.
 *
 * Both icons carry their state in the accent, which is this design's ordinary use of it —
 * marking the thing that is currently doing something. A tinted funnel is the only thing on
 * screen saying "these results are narrower than they look", so it is not decoration: without
 * it, a filter set and forgotten is a library that has silently lost 170 exercises.
 *
 * @param compact the bar has a navigation icon and a title to fit as well, so `+ NEW` is drawn
 *   as a `+`. The picker's does: two icons and the word leave about 128dp for "Add exercises",
 *   which truncates it to "Add exerci…" — and a title the user cannot read is a worse trade
 *   than a glyph they can. The tab's header has the whole width and keeps the word.
 */
@Composable
private fun LibraryActions(
    searchExpanded: Boolean,
    hasChipFilters: Boolean,
    onToggleSearch: () -> Unit,
    onOpenFilters: () -> Unit,
    onNewExercise: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggleSearch) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = stringResource(R.string.library_search),
                tint = if (searchExpanded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onOpenFilters) {
            Icon(
                // Filled when it is doing something, so the state survives being read at a
                // glance in bad light — a tint alone is a hue difference on a 24dp glyph.
                imageVector = if (hasChipFilters) {
                    Icons.Filled.FilterAlt
                } else {
                    Icons.Outlined.FilterAlt
                },
                contentDescription = stringResource(R.string.library_filters),
                tint = if (hasChipFilters) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (compact) {
            IconButton(onClick = onNewExercise) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.library_new_description),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            TextButton(onClick = onNewExercise) {
                Eyebrow(
                    text = stringResource(R.string.library_new),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * The picker's bar: the way back, what it is for, how much is picked, and `+ NEW`.
 *
 * @param replacing whether the picker is filling one slot rather than adding to the workout.
 *   The title is the one place that says so before a row is tapped, and "Add exercises" over a
 *   screen that will replace one is the wrong answer to "what am I doing here".
 * @param onBack the way out — nullable because the screen's is, the tab having none. A picker
 *   composed without one draws no arrow rather than one that goes nowhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerTopBar(
    state: ExerciseLibraryUiState,
    replacing: Boolean,
    searchExpanded: Boolean,
    onBack: (() -> Unit)?,
    onToggleSearch: () -> Unit,
    onOpenFilters: () -> Unit,
    onNewExercise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        TopAppBar(
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.library_back),
                        )
                    }
                }
            },
            title = {
                Column {
                    Text(
                        text = stringResource(
                            if (replacing) {
                                R.string.library_title_replace
                            } else {
                                R.string.library_title_select
                            },
                        ),
                        // `titleMedium`, not the `titleLarge` a bar would usually carry: a
                        // back arrow and three actions leave about 130dp for the title on a
                        // 360dp screen, and at `titleLarge` "Add exercises" wraps to two
                        // lines and takes the eyebrow under it off the bar.
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Eyebrow(text = state.subtitle(selecting = true))
                }
            },
            actions = {
                LibraryActions(
                    searchExpanded = searchExpanded,
                    hasChipFilters = state.hasChipFilters,
                    onToggleSearch = onToggleSearch,
                    onOpenFilters = onOpenFilters,
                    onNewExercise = onNewExercise,
                    compact = true,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        // The wireframes rule off every bar rather than floating it on elevation. A hairline
        // is legible in a gym; a 1dp shadow is not.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * `873 EXERCISES`, `12 OF 873`, or `3 SELECTED` — the count, in whichever bar is drawn.
 *
 * Three phrasings because the number means three different things, and one format string with
 * a count in it would leave the reader working out which. It is what tells somebody mid-search
 * whether their filters are doing anything.
 *
 * Unlike `SessionsHeader`, this one keeps drawing while `isLoading` — deliberately, because the
 * two flags do not mean the same thing. There, loading is "the read has not landed", true once
 * and never again. Here it also covers "the rows in hand answer a query the user has moved on
 * from" (see `ExerciseLibraryViewModel.buildState`), which is true for a beat after every
 * keystroke — and a count that blanked and returned as somebody typed would be worse than a
 * stale one. The first frames can read `0 EXERCISES` against a catalogue of 873; the seeded
 * install makes that a frame rather than a state anybody sits in.
 */
@Composable
private fun ExerciseLibraryUiState.subtitle(selecting: Boolean): String = when {
    selecting -> pluralStringResource(R.plurals.library_selected, selection.size, selection.size)
    hasFilters -> stringResource(R.string.library_count_filtered, matchCount, catalogueSize)
    else -> pluralStringResource(R.plurals.library_count, catalogueSize, catalogueSize)
}

/**
 * The picker's confirm bar.
 *
 * Disabled rather than hidden while nothing is selected, so it is clear from the moment the
 * screen opens what it is for and where the way out is.
 *
 * [replacing] changes what it says rather than what it does: one exercise chosen for a slot is
 * a swap, and "Add 1 exercise" over a workout that is about to have one *replaced* describes
 * somebody else's action. The count is still what enables it — the picker takes at most one in
 * that mode, so the two readings never disagree.
 *
 * The navigation-bar inset is asked for rather than assumed: under the app shell it resolves
 * to nothing, because `OpenGymApp` pads the graph for the system bars and consumes them, and
 * it is the whole inset anywhere this is hosted without that. Asking is what keeps the button
 * off the gesture handle in both cases without stacking two navigation bars' worth of space
 * under it in the first.
 */
@Composable
internal fun ConfirmSelectionBar(
    count: Int,
    replacing: Boolean,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Button(
            onClick = onConfirm,
            enabled = count > 0,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.screen),
        ) {
            Text(
                text = when {
                    replacing && count == 0 -> stringResource(R.string.library_confirm_replace_none)
                    replacing -> stringResource(R.string.library_confirm_replace)
                    count == 0 -> stringResource(R.string.library_confirm_none)
                    else -> pluralStringResource(R.plurals.library_confirm, count, count)
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
