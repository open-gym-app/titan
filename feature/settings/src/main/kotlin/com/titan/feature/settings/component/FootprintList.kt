package com.titan.feature.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.DataFootprint
import com.titan.core.ui.format.formatCount
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.R

/**
 * What is about to be lost, one line per kind, counts right-aligned in the mono numeric role
 * so they form a column the eye can total.
 *
 * The counts, per kind — not "all your data", which nobody weighs. Only non-zero lines are
 * listed, so the list is as long as the user's history is real; a device with nothing logged
 * gets nothing rather than a column of zeroes.
 *
 * Shared by both destructive confirmations, and that is the point: a wipe and a restore
 * destroy the same rows, so they must describe them in the same words. Two copies would be
 * two chances to disagree about what a restore costs. The one difference is the progress
 * photos, which a wipe erases and a restore leaves alone, and the caller says which it is.
 *
 * @param includePhotos whether the progress photos are among what is lost. True for a wipe, which
 *   erases them; false for a restore, which leaves them exactly as they are — see `ProgressPhoto`.
 * @param settingsLine what happens to the user's settings, drawn at the end without a count.
 *   Always shown and never the same sentence twice: a wipe resets them to a fresh install's
 *   defaults, a restore replaces them with the file's. They do not survive either way, and a
 *   confirmation that said nothing about them would be a surprise on the next launch — but one
 *   that said the *wrong* thing would be worse, which is why the caller supplies the words
 *   rather than a flag.
 */
@Composable
fun FootprintList(
    footprint: DataFootprint,
    settingsLine: String,
    modifier: Modifier = Modifier,
    includePhotos: Boolean = false,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        FootprintLine(footprint.sessions, R.plurals.settings_count_sessions)
        FootprintLine(footprint.sets, R.plurals.settings_count_sets)
        FootprintLine(footprint.templates, R.plurals.settings_count_templates)
        FootprintLine(footprint.personalRecords, R.plurals.settings_count_personal_records)
        FootprintLine(footprint.customExercises, R.plurals.settings_count_custom_exercises)
        FootprintLine(footprint.bodyMeasurements, R.plurals.settings_count_body_measurements)
        if (includePhotos) FootprintLine(footprint.progressPhotos, R.plurals.settings_count_progress_photos)

        Text(
            text = settingsLine,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One `184  sessions` line. Renders nothing at all when the count is zero. */
@Composable
private fun FootprintLine(
    count: Int,
    pluralId: Int,
) {
    if (count == 0) return

    Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
        Text(
            text = formatCount(count),
            style = TitanTheme.typography.numeric,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(COUNT_COLUMN_WIDTH),
        )
        Text(
            text = pluralStringResource(pluralId, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Wide enough for five digits and a group separator — `12,345` — which is more sets than any
 * plausible history and keeps the labels aligned whatever the numbers are.
 */
private val COUNT_COLUMN_WIDTH = 64.dp
