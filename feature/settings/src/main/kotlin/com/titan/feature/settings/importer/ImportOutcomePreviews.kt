package com.titan.feature.settings.importer

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.domain.importer.ImportSkips
import com.titan.core.domain.model.ThemePreference

/* How an import ends: the report, with and without skips, every refusal, an interrupted and a stopped import. */

@Preview(name = "Import - report", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportReportPreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.Completed(HistoryImportSource.STRONG, SAMPLE_REPORT)))
}

/** Everything a report can say at once: duplicates, unusable entries and photos that failed. */
@Preview(name = "Import - report with skips", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP + 400)
@Composable
fun ImportReportWithSkipsPreview() {
    PreviewScreen(
        ImportUiState(
            stage = ImportStage.Completed(
                HistoryImportSource.HEVY,
                SAMPLE_REPORT.copy(
                    measurementsImported = 88,
                    photosImported = 38,
                    photosFailed = 2,
                    skipped = ImportSkips(
                        duplicateWorkouts = 14,
                        invalidSets = 3,
                        duplicateMeasurements = 2,
                        invalidPhotos = 1,
                    ),
                ),
            ),
        ),
    )
}

/** The same file run twice: everything was a duplicate, and nothing new was earned. */
@Preview(name = "Import - report of a re-run, dark", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportReportRerunPreview() {
    PreviewScreen(
        ImportUiState(
            stage = ImportStage.Completed(
                HistoryImportSource.STRONG,
                HistoryImportReport(
                    workoutsImported = 0,
                    setsImported = 0,
                    exercisesMatched = 0,
                    exercisesCreated = 0,
                    templatesImported = 0,
                    measurementsImported = 0,
                    skipped = ImportSkips(duplicateWorkouts = 312),
                    recordsSet = 0,
                    xpEarned = 0,
                    achievementsUnlocked = 0,
                    questsCompleted = 0,
                    collectiblesFound = 0,
                ),
            ),
        ),
        ThemePreference.DARK,
    )
}

@Preview(
    name = "Import - report, twice the font",
    showBackground = true,
    heightDp = FULL_SCREEN_HEIGHT_DP * 2,
    fontScale = 2f,
)
@Composable
fun ImportReportLargeFontPreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.Completed(HistoryImportSource.STRONG, SAMPLE_REPORT)))
}

@Preview(name = "Import - failed, not recognised", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportFailedNotRecognisedPreview() {
    PreviewScreen(
        ImportUiState(stage = ImportStage.Failed(HistoryImportSource.STRONG, HistoryImportError.NotRecognised)),
    )
}

@Preview(name = "Import - failed, malformed", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportFailedMalformedPreview() {
    PreviewScreen(
        ImportUiState(stage = ImportStage.Failed(HistoryImportSource.FITBOD, HistoryImportError.Malformed("row 4"))),
    )
}

@Preview(name = "Import - failed, too large", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportFailedTooLargePreview() {
    PreviewScreen(
        ImportUiState(
            stage = ImportStage.Failed(HistoryImportSource.JEFIT, HistoryImportError.TooLarge(limitBytes = 33_554_432)),
        ),
    )
}

@Preview(name = "Import - failed, unreadable", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportFailedUnreadablePreview() {
    PreviewScreen(
        ImportUiState(stage = ImportStage.Failed(HistoryImportSource.HEVY, HistoryImportError.Unreadable("revoked"))),
    )
}

@Preview(name = "Import - failed, nothing to import", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportFailedNothingPreview() {
    PreviewScreen(
        ImportUiState(stage = ImportStage.Failed(HistoryImportSource.PLAIN_CSV, HistoryImportError.NothingToImport)),
    )
}

@Preview(name = "Import - failed, storage, dark", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportFailedStoragePreview() {
    PreviewScreen(
        ImportUiState(
            stage = ImportStage.Failed(HistoryImportSource.STRONGLIFTS, HistoryImportError.StorageFailure("full")),
        ),
        ThemePreference.DARK,
    )
}

@Preview(name = "Import - interrupted", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportInterruptedPreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.Interrupted(SAMPLE_REQUEST)))
}

@Preview(name = "Import - stopped during photos", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportStoppedPhotosPreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.Stopped(HistoryImportSource.HEVY, ImportStop.PHOTOS_SKIPPED)))
}
