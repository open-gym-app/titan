package com.titan.feature.settings.importer.component

import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.domain.importer.ImportSkips
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.R
import com.titan.feature.settings.importer.displayName

/**
 * What a finished import did, in four blocks: what landed, how its exercises were filed, what
 * was left out, and what the history earned.
 *
 * ### One summary, not a celebration per record
 *
 * A finished workout gets its records and unlocks announced one by one. A ten-year history
 * would announce hundreds, so the replayed history's records, XP, achievements, quests and
 * collectibles are counted here once, as lines like everything else on the card.
 *
 * ### What was left out is said plainly
 *
 * A partial import should not be a surprise found later, so a non-empty [ImportSkips] gets a
 * block of its own naming every kind and count — duplicates already on the phone apart from
 * entries that could not be used. Photos that would not store are not a failed import: the
 * history is in, and the sentence says a second run stores only what is missing.
 *
 * @param onOpenExerciseLibrary opens the exercise library, offered when the import created
 *   custom exercises the user may want to rename or merge.
 */
@Composable
internal fun ImportReportPanel(
    source: HistoryImportSource,
    report: HistoryImportReport,
    onOpenExerciseLibrary: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section)) {
        Text(
            text = stringResource(R.string.import_done_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        ReportBlock(title = stringResource(R.string.import_done_added, source.displayName())) {
            CountLine(R.plurals.import_report_workouts, report.workoutsImported, always = true)
            CountLine(R.plurals.import_report_sets, report.setsImported, always = true)
            CountLine(R.plurals.import_report_templates, report.templatesImported)
            CountLine(R.plurals.import_report_measurements, report.measurementsImported)
            CountLine(R.plurals.import_report_photos, report.photosImported)
        }

        if (report.photosFailed > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.import_report_photos_failed,
                    report.photosFailed,
                    formatCount(report.photosFailed),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (report.exercisesMatched + report.exercisesCreated > 0) {
            ReportBlock(title = stringResource(R.string.import_report_exercises_title)) {
                CountLine(R.plurals.import_report_matched, report.exercisesMatched)
                CountLine(R.plurals.import_report_created, report.exercisesCreated)
            }
            if (report.exercisesCreated > 0) {
                TextButton(onClick = onOpenExerciseLibrary) {
                    Text(text = stringResource(R.string.import_report_open_library))
                }
            }
        }

        if (!report.skipped.isEmpty) {
            ReportBlock(title = stringResource(R.string.import_report_skipped_title)) {
                Line(text = stringResource(R.string.import_report_skipped_intro))
                SkippedLines(report.skipped)
            }
        }

        ReportBlock(title = stringResource(R.string.import_report_earned_title)) {
            if (report.earnedNothing) {
                Line(text = stringResource(R.string.import_report_nothing_earned))
            } else {
                CountLine(R.plurals.import_report_records, report.recordsSet)
                if (report.xpEarned > 0) {
                    Line(
                        text = stringResource(R.string.import_report_xp, formatCount(report.xpEarned)),
                    )
                }
                CountLine(R.plurals.import_report_achievements, report.achievementsUnlocked)
                CountLine(R.plurals.import_report_quests, report.questsCompleted)
                CountLine(R.plurals.import_report_collectibles, report.collectiblesFound)
            }
        }

        Text(
            text = stringResource(R.string.import_guide_records, source.displayName()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.import_done_action))
        }
    }
}

/** Every kind of entry left out, each with its count; kinds with none are not listed. */
@Composable
private fun SkippedLines(skipped: ImportSkips) {
    CountLine(R.plurals.import_report_duplicate_workouts, skipped.duplicateWorkouts)
    CountLine(R.plurals.import_report_invalid_workouts, skipped.invalidWorkouts)
    CountLine(R.plurals.import_report_invalid_sets, skipped.invalidSets)
    CountLine(R.plurals.import_report_duplicate_templates, skipped.duplicateTemplates)
    CountLine(R.plurals.import_report_invalid_templates, skipped.invalidTemplates)
    CountLine(R.plurals.import_report_duplicate_measurements, skipped.duplicateMeasurements)
    CountLine(R.plurals.import_report_invalid_measurements, skipped.invalidMeasurements)
    CountLine(R.plurals.import_report_duplicate_photos, skipped.duplicatePhotos)
    CountLine(R.plurals.import_report_invalid_photos, skipped.invalidPhotos)
}

/** Whether the replay earned nothing at all — a second run of a file already imported. */
private val HistoryImportReport.earnedNothing: Boolean
    get() = recordsSet == 0 && xpEarned == 0L && achievementsUnlocked == 0 && questsCompleted == 0 &&
        collectiblesFound == 0

@Composable
private fun ReportBlock(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
        SectionHeading(text = title)
        TitanCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(TitanTheme.spacing.card),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm),
            ) {
                content()
            }
        }
    }
}

/** "312 workouts", or nothing for a zero unless [always] — a card of "0 photos" says nothing. */
@Composable
private fun CountLine(
    @PluralsRes plural: Int,
    count: Int,
    always: Boolean = false,
) {
    if (count == 0 && !always) return
    Line(text = pluralStringResource(plural, count, formatCount(count)))
}

@Composable
private fun Line(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}
