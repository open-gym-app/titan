package com.titan.feature.settings.backup.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.backup.AutoBackupSchedule
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.R
import com.titan.feature.settings.backup.statusLine

/**
 * The weekly automatic backup: the switch, what it last did, and what it does not protect.
 *
 * ### Under the export, above the restore
 *
 * It is the same thing the export button does, on a schedule, so it belongs beside it rather
 * than in a section of its own further down — and it must stay above the restore, which is the
 * one destructive control on the screen and the last thing a user should meet.
 *
 * ### The uninstall sentence is not a disclaimer
 *
 * It is the most important line in this section. These files live in app-specific storage,
 * which means they go when the app does, and "delete all data" deletes them too; somebody who
 * believes a weekly automatic backup will be there after a reinstall or a wipe has been actively
 * misled, and the manual export directly above is exactly what exists for that case. So it is
 * stated on the screen, next to the toggle, in the same words whether the toggle is on or off.
 *
 * It also says the other half of the files' lifetime: turning the switch off stops new backups
 * and deletes none of the ones already written. Retention only runs as part of a backup, so
 * with the schedule off the last three stay until the app goes or the user deletes all data.
 * That is the safer default — switching a schedule off is not asking for the copies it made to
 * be destroyed — but it is a user's training history sitting on the phone, and somebody turning
 * the feature off to be rid of it has to be told it is still there.
 *
 * @param schedule what the store holds: the flag, when a run last wrote a file, and whether the
 *   last one gave up. Never the ViewModel's own copy — a worker records its run with no screen
 *   alive, and the row has to draw what that worker wrote.
 * @param saveFailed whether the last flip could not be stored. The switch already snaps back on
 *   its own, since it is drawn from [schedule]; this is what says why it did.
 * @param onToggle reports the value the switch moved *to*, rather than "flip it": a second tap
 *   landing while the first write is in flight would otherwise invert a value that has already
 *   changed underneath it.
 */
@Composable
internal fun AutoBackupSection(
    schedule: AutoBackupSchedule,
    saveFailed: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        SectionHeading(text = stringResource(R.string.backup_auto_title))
        Text(
            text = stringResource(R.string.backup_auto_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The whole row toggles, not the switch alone: a full-width row is a far better target
        // than the control at the end of it, and giving the switch its own click would leave
        // two overlapping controls saying the same thing to a screen reader — the rule the
        // exercise library's rows already follow. `onCheckedChange = null` is what makes the
        // switch a drawing of the state rather than a second control.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = schedule.enabled,
                    role = Role.Switch,
                    onValueChange = onToggle,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.backup_auto_action),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = schedule.statusLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = schedule.enabled, onCheckedChange = null)
        }

        // Only while it is on: a schedule that is off has no next run to try again, and the
        // sentence would be reporting on a feature the user has already turned away from.
        if (schedule.enabled && schedule.hasFailed) {
            Text(
                text = stringResource(R.string.backup_auto_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (saveFailed) {
            Text(
                text = stringResource(R.string.backup_auto_save_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            text = stringResource(R.string.backup_auto_uninstall),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

/**
 * Every state the row has, one under the other.
 *
 * A catalogue rather than four screen references, for the reason `PreferenceRow`'s is one: what
 * changes between these is three lines of text in two colours, and four whole-screen captures
 * of that would record the same scrolling page four times to compare a sentence.
 */
@Preview(name = "Auto backup section", showBackground = true, widthDp = 360, heightDp = 700)
@Composable
fun AutoBackupSectionPreview() {
    TitanTheme {
        AutoBackupSectionCatalogue()
    }
}

@Preview(name = "Auto backup section, dark", showBackground = true, widthDp = 360, heightDp = 700)
@Composable
fun AutoBackupSectionDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        AutoBackupSectionCatalogue()
    }
}

@Composable
private fun AutoBackupSectionCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(TitanTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
    ) {
        AutoBackupSection(schedule = AutoBackupSchedule(), saveFailed = false, onToggle = {})
        AutoBackupSection(
            schedule = AutoBackupSchedule(enabled = true, lastSuccessAt = SAMPLE_LAST_BACKUP_AT),
            saveFailed = false,
            onToggle = {},
        )
        AutoBackupSection(
            schedule = AutoBackupSchedule(
                enabled = true,
                lastSuccessAt = SAMPLE_LAST_BACKUP_AT,
                lastFailureAt = SAMPLE_LAST_FAILURE_AT,
            ),
            saveFailed = false,
            onToggle = {},
        )
    }
}

/** 2026-04-26, the Sunday the wireframe's "last: APR 26" names. */
private const val SAMPLE_LAST_BACKUP_AT = 1_777_161_600_000L

/** A week later, so the failure is the newer of the two facts the row carries. */
private const val SAMPLE_LAST_FAILURE_AT = 1_777_766_400_000L
