package com.titan.feature.settings.importer

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.domain.importer.ImportSkips
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.theme.TitanTheme

/*
 * The import screen's previews: each is a screenshot reference and an accessibility check (see
 * docs/ACCESSIBILITY.md). One per stage the spec names — every phase, every refusal, a report
 * with and without skips — plus the dialogs, a dark variant of each kind and the twice-the-font
 * captures. Their own file so the screen's file stays about the screen, and in two: the stages
 * before and during an import here, how one ends in `ImportOutcomePreviews.kt`.
 */

/** Tall enough for the longest stage — a report with every block — to be captured whole. */
internal const val FULL_SCREEN_HEIGHT_DP = 1200

internal val SAMPLE_REQUEST = HistoryImportRequest(HistoryImportSource.STRONG, "content://downloads/strong.csv")

internal val SAMPLE_REPORT = HistoryImportReport(
    workoutsImported = 312,
    setsImported = 9_840,
    exercisesMatched = 41,
    exercisesCreated = 6,
    templatesImported = 0,
    measurementsImported = 0,
    skipped = ImportSkips(),
    recordsSet = 140,
    xpEarned = 48_250,
    achievementsUnlocked = 11,
    questsCompleted = 23,
    collectiblesFound = 7,
)

@Composable
internal fun PreviewScreen(
    state: ImportUiState,
    theme: ThemePreference = ThemePreference.LIGHT,
) {
    TitanTheme(theme = theme) {
        ImportScreen(
            state = state,
            onEvent = {},
            onBack = {},
            onChooseFile = {},
            onOpenFormat = {},
            onOpenExerciseLibrary = {},
        )
    }
}

@Preview(name = "Import - choose an app", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportChooseSourcePreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.ChooseSource))
}

/** Every label at twice its size, on the stage with the most text in rows. */
@Preview(
    name = "Import - choose an app, twice the font",
    showBackground = true,
    heightDp = FULL_SCREEN_HEIGHT_DP * 2,
    fontScale = 2f,
)
@Composable
fun ImportChooseSourceLargeFontPreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.ChooseSource))
}

@Preview(name = "Import - Strong guide", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportGuidePreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.Guide(HistoryImportSource.STRONG)))
}

/** FitNotes, whose guide has to say which of its two exports is not the one. */
@Preview(name = "Import - FitNotes guide, dark", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportGuideFitNotesPreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.Guide(HistoryImportSource.FITNOTES)), ThemePreference.DARK)
}

/** Plain CSV, whose guide is the format and a link to its specification. */
@Preview(name = "Import - plain CSV guide", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportGuidePlainCsvPreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.Guide(HistoryImportSource.PLAIN_CSV)))
}

@Preview(
    name = "Import - guide, twice the font",
    showBackground = true,
    heightDp = FULL_SCREEN_HEIGHT_DP * 2,
    fontScale = 2f,
)
@Composable
fun ImportGuideLargeFontPreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.Guide(HistoryImportSource.HEVY)))
}

@Preview(name = "Import - a workout in progress", showBackground = true)
@Composable
fun ImportActiveWorkoutPreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.Guide(HistoryImportSource.STRONG), pendingRequest = SAMPLE_REQUEST))
}

@Preview(name = "Import - reading", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportReadingPreview() {
    PreviewScreen(ImportUiState(stage = ImportStage.Running(HistoryImportSource.STRONG, HistoryImportProgress.Reading)))
}

@Preview(name = "Import - writing", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportWritingPreview() {
    PreviewScreen(
        ImportUiState(
            stage = ImportStage.Running(
                HistoryImportSource.HEVY,
                HistoryImportProgress.Writing(workoutsWritten = 1_204, workoutsTotal = 3_120),
            ),
        ),
    )
}

@Preview(name = "Import - storing photos, dark", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun ImportStoringPhotosPreview() {
    PreviewScreen(
        ImportUiState(
            stage = ImportStage.Running(
                HistoryImportSource.HEVY,
                HistoryImportProgress.StoringPhotos(photosStored = 12, photosTotal = 40),
            ),
        ),
        ThemePreference.DARK,
    )
}

@Preview(name = "Import - cancel while writing", showBackground = true)
@Composable
fun ImportCancelPreview() {
    PreviewScreen(
        ImportUiState(
            stage = ImportStage.Running(
                HistoryImportSource.HEVY,
                HistoryImportProgress.Writing(workoutsWritten = 1_204, workoutsTotal = 3_120),
            ),
            confirmingCancel = true,
        ),
    )
}

@Preview(name = "Import - stop while storing photos", showBackground = true)
@Composable
fun ImportStopPhotosPreview() {
    PreviewScreen(
        ImportUiState(
            stage = ImportStage.Running(
                HistoryImportSource.HEVY,
                HistoryImportProgress.StoringPhotos(photosStored = 12, photosTotal = 40),
            ),
            confirmingCancel = true,
        ),
    )
}
