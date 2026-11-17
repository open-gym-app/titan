package com.titan.feature.settings.importer

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.feature.settings.R

/*
 * What the import screen calls each source and each refusal.
 *
 * Here rather than in `:core:ui`'s label files because one screen draws them: the import is the
 * only place a user meets the list of apps, and a second screen that needed it would move it.
 * Every `when` is exhaustive, so a source added to `HistoryImportSource` or a refusal added to
 * `HistoryImportError` fails to compile here rather than reaching the user unnamed.
 */

/**
 * Where the plain CSV format is specified, with its two example files beside it.
 *
 * GitHub rather than a copy in the app: the document is long, versioned with the parser that
 * reads it, and the tests import its example files as published — so the copy a user reads is
 * the one the code is held to. Opening it leaves the app for the browser; the app itself has no
 * network access.
 */
internal const val PLAIN_CSV_FORMAT_URL = "https://github.com/open-gym-app/titan/blob/main/docs/import-csv.md"

/** The app's own name, or "Plain CSV". */
@Composable
internal fun HistoryImportSource.displayName(): String = stringResource(nameRes)

@get:StringRes
private val HistoryImportSource.nameRes: Int
    get() = when (this) {
        HistoryImportSource.STRONG -> R.string.import_source_strong
        HistoryImportSource.HEVY -> R.string.import_source_hevy
        HistoryImportSource.FITNOTES -> R.string.import_source_fitnotes
        HistoryImportSource.JEFIT -> R.string.import_source_jefit
        HistoryImportSource.FITBOD -> R.string.import_source_fitbod
        HistoryImportSource.STRONGLIFTS -> R.string.import_source_stronglifts
        HistoryImportSource.PLAIN_CSV -> R.string.import_source_plain_csv
    }

/** What an export of this source brings, in a few words, for the list of apps. */
@Composable
internal fun HistoryImportSource.detail(): String =
    stringResource(
        when (this) {
            HistoryImportSource.STRONG -> R.string.import_source_strong_detail
            HistoryImportSource.HEVY -> R.string.import_source_hevy_detail
            HistoryImportSource.FITNOTES -> R.string.import_source_fitnotes_detail
            HistoryImportSource.JEFIT -> R.string.import_source_jefit_detail
            HistoryImportSource.FITBOD -> R.string.import_source_fitbod_detail
            HistoryImportSource.STRONGLIFTS -> R.string.import_source_stronglifts_detail
            HistoryImportSource.PLAIN_CSV -> R.string.import_source_plain_csv_detail
        },
    )

/**
 * How to get the file out of this source, step by step.
 *
 * Three steps for every source, which is what each needs: where the export is, what to save,
 * and picking it here. The menu names are left in the source app's own English where that is
 * what its screens say, so the words in the guide are the words on the button.
 */
@Composable
internal fun HistoryImportSource.guideSteps(): List<String> =
    when (this) {
        HistoryImportSource.STRONG -> listOf(
            R.string.import_guide_strong_step_1,
            R.string.import_guide_strong_step_2,
            R.string.import_guide_strong_step_3,
        )

        HistoryImportSource.HEVY -> listOf(
            R.string.import_guide_hevy_step_1,
            R.string.import_guide_hevy_step_2,
            R.string.import_guide_hevy_step_3,
        )

        HistoryImportSource.FITNOTES -> listOf(
            R.string.import_guide_fitnotes_step_1,
            R.string.import_guide_fitnotes_step_2,
            R.string.import_guide_fitnotes_step_3,
        )

        HistoryImportSource.JEFIT -> listOf(
            R.string.import_guide_jefit_step_1,
            R.string.import_guide_jefit_step_2,
            R.string.import_guide_jefit_step_3,
        )

        HistoryImportSource.FITBOD -> listOf(
            R.string.import_guide_fitbod_step_1,
            R.string.import_guide_fitbod_step_2,
            R.string.import_guide_fitbod_step_3,
        )

        HistoryImportSource.STRONGLIFTS -> listOf(
            R.string.import_guide_stronglifts_step_1,
            R.string.import_guide_stronglifts_step_2,
            R.string.import_guide_stronglifts_step_3,
        )

        HistoryImportSource.PLAIN_CSV -> listOf(
            R.string.import_guide_plain_csv_step_1,
            R.string.import_guide_plain_csv_step_2,
            R.string.import_guide_plain_csv_step_3,
        )
    }.map { stringResource(it) }

/** What comes across from this source, and what does not — said before the file is picked. */
@Composable
internal fun HistoryImportSource.guideNote(): String =
    stringResource(
        when (this) {
            HistoryImportSource.STRONG -> R.string.import_guide_strong_note
            HistoryImportSource.HEVY -> R.string.import_guide_hevy_note
            HistoryImportSource.FITNOTES -> R.string.import_guide_fitnotes_note
            HistoryImportSource.JEFIT -> R.string.import_guide_jefit_note
            HistoryImportSource.FITBOD -> R.string.import_guide_fitbod_note
            HistoryImportSource.STRONGLIFTS -> R.string.import_guide_stronglifts_note
            HistoryImportSource.PLAIN_CSV -> R.string.import_guide_plain_csv_note
        },
    )

/**
 * The sentence a refusal is shown as, naming [source] where the refusal is about it.
 *
 * Never the error's `reason`: that is English for a log, and says what the parser saw rather
 * than what the user can do. Each sentence ends on the thing to do next.
 */
@Composable
internal fun HistoryImportError.message(source: HistoryImportSource): String {
    val name = source.displayName()
    return when (this) {
        HistoryImportError.NotRecognised -> stringResource(R.string.import_failed_not_recognised, name)

        is HistoryImportError.Malformed -> stringResource(R.string.import_failed_malformed, name)

        // The platform's formatter, so `32 MB` follows the locale and the storage screen's units.
        is HistoryImportError.TooLarge -> stringResource(
            R.string.import_failed_too_large,
            Formatter.formatShortFileSize(LocalContext.current, limitBytes),
        )

        is HistoryImportError.Unreadable -> stringResource(R.string.import_failed_unreadable)

        HistoryImportError.NothingToImport -> stringResource(R.string.import_failed_nothing)

        is HistoryImportError.StorageFailure -> stringResource(R.string.import_failed_storage)
    }
}

/** The sentence a stopped import is shown as. */
@Composable
internal fun ImportStop.message(source: HistoryImportSource): String =
    when (this) {
        ImportStop.NOTHING_IMPORTED -> stringResource(R.string.import_stopped_nothing)
        ImportStop.PHOTOS_SKIPPED -> stringResource(R.string.import_stopped_photos, source.displayName())
        ImportStop.UNKNOWN -> stringResource(R.string.import_stopped_unknown)
    }
