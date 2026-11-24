package com.titan.feature.settings.importer.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.PreferenceGroup
import com.titan.core.ui.component.PreferenceRow
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.R
import com.titan.feature.settings.importer.detail
import com.titan.feature.settings.importer.displayName
import com.titan.feature.settings.importer.guideNote
import com.titan.feature.settings.importer.guideSteps

/*
 * The two stages before an import: which app, and how to get its file out.
 */

/**
 * Every app the import reads, as the settings rows they look like, under the sentence that
 * matters most on this screen: an import adds to the history, where a restore replaces it.
 */
@Composable
internal fun ImportSourceList(
    onChoose: (HistoryImportSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section)) {
        Text(
            text = stringResource(R.string.import_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PreferenceGroup(title = stringResource(R.string.import_sources_title)) {
            HistoryImportSource.entries.forEachIndexed { index, source ->
                if (index > 0) HairlineDivider()
                PreferenceRow(
                    label = source.displayName(),
                    supporting = source.detail(),
                    onClick = { onChoose(source) },
                )
            }
        }
        Text(
            text = stringResource(R.string.import_sources_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * How to export from [source]: the steps, what comes across, what is worth knowing before the
 * import runs, and the button that opens the system file picker.
 *
 * The behaviour the engine promises is said here rather than after the import, because it is
 * what decides whether to run it: the history already on the phone stays, a second run of the
 * same file is harmless, records are recalculated rather than copied, and two things an older
 * history cannot rewrite.
 *
 * @param onOpenFormat opens the plain CSV specification. Only the plain CSV guide offers it.
 */
@Composable
internal fun ImportGuide(
    source: HistoryImportSource,
    onChooseFile: () -> Unit,
    onOpenFormat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = source.displayName()
    val isPlainCsv = source == HistoryImportSource.PLAIN_CSV

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section)) {
        Text(
            text = stringResource(R.string.import_guide_title, name),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
            SectionHeading(
                text = stringResource(
                    if (isPlainCsv) R.string.import_guide_format_title else R.string.import_guide_steps_title,
                ),
            )
            source.guideSteps().forEachIndexed { index, step -> GuideStep(number = index + 1, text = step) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
            SectionHeading(text = stringResource(R.string.import_guide_note_title))
            Text(
                text = source.guideNote(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (isPlainCsv) {
                TextButton(onClick = onOpenFormat) {
                    Text(text = stringResource(R.string.import_guide_csv_link))
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
            SectionHeading(text = stringResource(R.string.import_guide_good_to_know))
            TitanCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(TitanTheme.spacing.card),
                    verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
                ) {
                    listOf(
                        stringResource(R.string.import_guide_merge),
                        stringResource(R.string.import_guide_twice),
                        stringResource(R.string.import_guide_records, name),
                        stringResource(R.string.import_guide_streak),
                    ).forEach { fact ->
                        Text(
                            text = fact,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Button(onClick = onChooseFile, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.import_guide_choose_file))
        }
    }
}

/**
 * One step: its number in the numeric role, and the sentence. Read as one node, "Step 2: …",
 * so a screen reader does not say a bare figure and then a sentence a swipe later.
 */
@Composable
private fun GuideStep(
    number: Int,
    text: String,
) {
    val figure = formatCount(number)
    val spoken = stringResource(R.string.import_guide_step_description, figure, text)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
    ) {
        Text(
            text = figure,
            style = TitanTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = STEP_NUMBER_WIDTH),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Room for a one-digit step number in the numeric role, so every step's text starts on one edge. */
private val STEP_NUMBER_WIDTH = 16.dp
