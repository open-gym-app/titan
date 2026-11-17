package com.titan.feature.settings.importer

import androidx.compose.runtime.Immutable
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryImportSource

/**
 * Everything the import screen draws, as one value.
 *
 * @param stage where the user is: choosing an app, reading its guide, or watching an import
 *   that is running or has ended. One field, because they are one screen at a time.
 * @param pendingRequest a picked file waiting on the "a workout is in progress" question.
 *   Non-null exactly while that dialog is up.
 * @param confirmingCancel whether the "stop the import?" question is up.
 */
@Immutable
data class ImportUiState(
    val stage: ImportStage = ImportStage.ChooseSource,
    val pendingRequest: HistoryImportRequest? = null,
    val confirmingCancel: Boolean = false,
)

/**
 * Where the import screen is.
 *
 * The first three are the screen's own; every other one is what the worker running the import
 * says, through [com.titan.core.domain.importer.HistoryImportRunner] — so leaving the screen
 * and coming back, or opening it from the notification in a new process, lands on the same
 * stage.
 */
sealed interface ImportStage {

    /**
     * The worker has not answered yet. Drawn as nothing rather than as the list of apps, which
     * would flash a choice over an import that is already running.
     */
    data object Loading : ImportStage

    /** Which app is the history in? */
    data object ChooseSource : ImportStage

    /** How to get the file out of [source], and the button that picks it. */
    data class Guide(val source: HistoryImportSource) : ImportStage

    /**
     * An import is running.
     *
     * @property progress [HistoryImportProgress.Reading], [HistoryImportProgress.Writing] or
     *   [HistoryImportProgress.StoringPhotos]. Its counts only ever move forward.
     */
    data class Running(
        val source: HistoryImportSource,
        val progress: HistoryImportProgress,
    ) : ImportStage

    /** The import finished; [report] says what it wrote, left out and earned. */
    data class Completed(
        val source: HistoryImportSource,
        val report: HistoryImportReport,
    ) : ImportStage

    /** Nothing was imported, for [error]'s reason. */
    data class Failed(
        val source: HistoryImportSource,
        val error: HistoryImportError,
    ) : ImportStage

    /**
     * The system stopped the last import before it finished — the app was closed part way.
     * What it had not saved was left out, and [request] can be run again as it was.
     */
    data class Interrupted(val request: HistoryImportRequest) : ImportStage

    /** The user stopped the import; [stop] says what that cost. */
    data class Stopped(
        val source: HistoryImportSource,
        val stop: ImportStop,
    ) : ImportStage
}

/** What stopping an import cost, which depends on when it was stopped. */
enum class ImportStop {

    /** Stopped while reading or writing the history: the transaction rolled back, nothing landed. */
    NOTHING_IMPORTED,

    /** Stopped while storing photos: the history had already committed; only photos were skipped. */
    PHOTOS_SKIPPED,

    /**
     * Stopped from the notification, or by a screen that is gone, so when is not known. The
     * sentence for it is true in both cases above.
     */
    UNKNOWN,
}
