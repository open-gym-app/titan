package com.titan.feature.settings.importer.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.R
import com.titan.feature.settings.backup.progressPercent
import com.titan.feature.settings.importer.ImportStop
import com.titan.feature.settings.importer.displayName
import com.titan.feature.settings.importer.message
import com.titan.core.ui.R as CoreR

/*
 * An import while it runs, and the three ways one can end without a report: refused,
 * interrupted by the system, or stopped by the user.
 */

/**
 * The running import: what it is doing, how far it has got, and the way to stop it.
 *
 * Reading is indeterminate — it covers parsing, waiting for a backup to finish and planning,
 * none of which has a count. Writing and storing photos are determinate, by workouts and by
 * photos. The cancel button says what it costs: before the history is saved it undoes all of
 * it, and while photos are stored it only stops the photos.
 */
@Composable
internal fun ImportProgressPanel(
    source: HistoryImportSource,
    progress: HistoryImportProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section)) {
        Text(
            text = stringResource(R.string.import_running_title, source.displayName()),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        when (progress) {
            is HistoryImportProgress.Writing -> CountedProgress(
                label = stringResource(R.string.import_writing),
                count = pluralStringResource(
                    R.plurals.import_writing_count,
                    progress.workoutsTotal,
                    formatCount(progress.workoutsWritten),
                    formatCount(progress.workoutsTotal),
                ),
                done = progress.workoutsWritten,
                total = progress.workoutsTotal,
            )

            is HistoryImportProgress.StoringPhotos -> {
                CountedProgress(
                    label = stringResource(R.string.import_photos),
                    count = pluralStringResource(
                        R.plurals.import_photos_count,
                        progress.photosTotal,
                        formatCount(progress.photosStored),
                        formatCount(progress.photosTotal),
                    ),
                    done = progress.photosStored,
                    total = progress.photosTotal,
                )
                Body(text = stringResource(R.string.import_photos_detail))
            }

            else -> {
                ReadingProgress()
                Body(text = stringResource(R.string.import_reading_detail))
            }
        }

        Body(text = stringResource(R.string.import_running_leave))

        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(
                    if (progress is HistoryImportProgress.StoringPhotos) R.string.import_stop_photos else R.string.import_cancel,
                ),
            )
        }
    }
}

/**
 * An indeterminate bar under its label, said once: there is no figure for a live region to
 * repeat while the file is read.
 */
@Composable
private fun ReadingProgress() {
    val label = stringResource(R.string.import_reading)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * A determinate bar, its label and its count, spoken as one polite live region.
 *
 * The spoken form is the label and a percentage in tens — "Saving workouts, 40%" — which is
 * what the backup screen's progress row says and for the same reason: a ten-year history moves
 * its count thousands of times, and TalkBack should speak at most ten of them. The drawn count
 * is exact; the spoken one is what is worth interrupting for.
 */
@Composable
private fun CountedProgress(
    label: String,
    count: String,
    done: Int,
    total: Int,
) {
    val spoken = if (total > 0) {
        stringResource(CoreR.string.figure_with_unit, label, progressPercent(done.toLong(), total.toLong()))
    } else {
        label
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total.toFloat() else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = count,
            style = TitanTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A refused import: its sentence, that nothing changed, and the two ways on. */
@Composable
internal fun ImportFailurePanel(
    source: HistoryImportSource,
    error: HistoryImportError,
    onTryAgain: () -> Unit,
    onChooseAnotherApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutcomePanel(
        title = stringResource(R.string.import_failed_title),
        sentences = listOf(error.message(source), stringResource(R.string.import_failed_safe)),
        primary = stringResource(R.string.import_try_again) to onTryAgain,
        secondary = stringResource(R.string.import_choose_another_app) to onChooseAnotherApp,
        isError = true,
        modifier = modifier,
    )
}

/** An import the system stopped part way: nothing landed, and it can run again as it was. */
@Composable
internal fun ImportInterruptedPanel(
    source: HistoryImportSource,
    onRunAgain: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutcomePanel(
        title = stringResource(R.string.import_interrupted_title),
        sentences = listOf(stringResource(R.string.import_interrupted_body, source.displayName())),
        primary = stringResource(R.string.import_run_again) to onRunAgain,
        secondary = stringResource(R.string.import_not_now) to onNotNow,
        modifier = modifier,
    )
}

/** An import the user stopped, and what that cost. */
@Composable
internal fun ImportStoppedPanel(
    source: HistoryImportSource,
    stop: ImportStop,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutcomePanel(
        title = stringResource(R.string.import_stopped_title),
        sentences = listOf(stop.message(source)),
        primary = stringResource(R.string.import_done_action) to onDone,
        secondary = null,
        modifier = modifier,
    )
}

@Composable
private fun OutcomePanel(
    title: String,
    sentences: List<String>,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>?,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        TitanCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(TitanTheme.spacing.card),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
            ) {
                sentences.forEach { sentence ->
                    Text(
                        text = sentence,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Button(onClick = primary.second, modifier = Modifier.fillMaxWidth()) {
            Text(text = primary.first)
        }
        secondary?.let { (label, onClick) ->
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text(text = label)
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The question before stopping, worded for what stopping costs now: everything, before the
 * history is saved; only the photos not yet stored, after.
 */
@Composable
internal fun CancelImportDialog(
    storingPhotos: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (storingPhotos) R.string.import_stop_photos_title else R.string.import_cancel_title,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                text = stringResource(
                    if (storingPhotos) R.string.import_stop_photos_body else R.string.import_cancel_body,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(
                        if (storingPhotos) R.string.import_stop_photos_confirm else R.string.import_cancel,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(
                        if (storingPhotos) R.string.import_stop_photos_keep else R.string.import_cancel_keep,
                    ),
                )
            }
        },
    )
}

/** The warning before an import that would hold up the workout in progress. */
@Composable
internal fun ActiveWorkoutDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.import_active_workout_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.import_active_workout_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = stringResource(R.string.import_active_workout_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.import_active_workout_dismiss)) }
        },
    )
}
