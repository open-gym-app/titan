package com.titan.feature.settings.importer

import com.titan.core.domain.importer.HistoryImportSource

/** Everything the import screen can report. One event per thing that happened, as elsewhere. */
sealed interface ImportEvent {

    /** An app was picked from the list; its guide opens. */
    data class SourceChosen(val source: HistoryImportSource) : ImportEvent

    /** Back from a guide to the list of apps. */
    data object GuideClosed : ImportEvent

    /**
     * The system document picker handed a file back. A cancelled picker sends nothing.
     *
     * @property uri the document, as a string, so the state carries no platform type.
     */
    data class FileChosen(val uri: String) : ImportEvent

    /** "Import now" under the warning that a workout is in progress. */
    data object StartConfirmed : ImportEvent

    /** "Not now" under the same warning: nothing starts. */
    data object StartDismissed : ImportEvent

    /** The running import's cancel button: asks before doing anything. */
    data object CancelRequested : ImportEvent

    /** The import is to stop. */
    data object CancelConfirmed : ImportEvent

    /** The import keeps going. */
    data object CancelDismissed : ImportEvent

    /** Run an interrupted import again, with the same file. */
    data object RunAgain : ImportEvent

    /** After a failure: back to the same app's guide, to pick a file again. */
    data object TryAgain : ImportEvent

    /** After a failure: back to the list, to pick a different app. */
    data object ChooseAnotherApp : ImportEvent

    /**
     * The outcome has been read — a report's Done, a stopped import's Done, an interrupted
     * one's Not now. The screen starts afresh.
     */
    data object OutcomeDismissed : ImportEvent
}
