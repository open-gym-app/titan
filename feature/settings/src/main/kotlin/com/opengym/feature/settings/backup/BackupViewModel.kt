package com.opengym.feature.settings.backup

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opengym.core.domain.backup.ExportSink
import com.opengym.core.domain.backup.ImportSource
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.backup.model.ImportProgress
import com.opengym.core.domain.repository.AutoBackupRepository
import com.opengym.core.domain.repository.LocalDataRepository
import com.opengym.core.domain.time.Clock
import com.opengym.core.domain.usecase.ExportUseCase
import com.opengym.core.domain.usecase.ImportUseCase
import com.opengym.core.domain.usecase.SetAutoBackupEnabledUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * The backup screen's state, and the four operations behind it.
 *
 * ### Where the file goes, and why this class knows
 *
 * `ExportUseCase` takes an [ExportSink] and `ImportUseCase` takes an [ImportSource]: neither
 * knows about files, content URIs or share sheets, which is what keeps them testable in a module
 * with no Android on its classpath. Somebody has to bridge that to the platform, and it is
 * here rather than in `:core:data` because what is being bridged is not app *state* — it is a
 * transient file the user is about to copy somewhere or hand to another app, and the two `Uri`s
 * that arrive from the `ActivityResultContract`s this screen launched. `InProgressSessionRepositoryImpl` is behind a
 * repository because the draft is durable state the app reads back; a backup is not.
 *
 * ### Written to a scratch file and moved into place
 *
 * A failed export leaves a truncated document by construction — the sink has already been
 * handed everything written so far — so the bytes go to a `.tmp` sibling and are renamed only
 * once the flow reports completion. That is the pattern `ExportSink` documents and the one
 * `InProgressSessionRepositoryImpl` already uses for the draft. A run that fails deletes the
 * scratch file rather than leaving a half-backup for somebody to restore from.
 *
 * ### Blocking work goes to `Dispatchers.IO`
 *
 * Opening a stream, writing tens of megabytes, copying a file and reading a document back are
 * all blocking,
 * and `viewModelScope` dispatches on Main. The dispatcher is named inline rather than injected
 * because the qualifier that would carry it lives in `:core:data`, which a feature module may
 * not depend on — see docs/ARCHITECTURE.md. `BackupViewModelTest` therefore awaits the state
 * it expects rather than advancing a virtual clock, which is honest: the work really is on
 * another thread.
 *
 * ### One operation at a time
 *
 * The three that move bytes — export, copy, restore — are guarded on [BackupUiState.isBusy].
 * Two exports racing would fight over one scratch path; an export racing a restore would read
 * rows out of a database being wiped; an export racing a copy would rewrite the file the copy
 * is reading. None is reachable through the UI — the buttons go inert — and the guard is what
 * makes that a property of the ViewModel rather than of the screen drawing it.
 *
 * The fourth, the auto-backup toggle, is deliberately outside that: it writes one flag and
 * enqueues a job, so it reads no rows and touches no file. See [setAutoBackup].
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val exportBackup: ExportUseCase,
    private val importBackup: ImportUseCase,
    private val setAutoBackupEnabled: SetAutoBackupEnabledUseCase,
    private val clock: Clock,
    localDataRepository: LocalDataRepository,
    autoBackupRepository: AutoBackupRepository,
) : ViewModel() {

    /** What the screen owns, as opposed to what the store does. */
    private val screenState = MutableStateFlow(ScreenState())

    /**
     * One toggle write at a time, in the order the taps arrived.
     *
     * Not the [BackupUiState.isBusy] guard — this is the one control that stays live during an
     * export — but a lock over itself. Two taps land as two coroutines, and unserialized their
     * two halves interleave: "on" storing the flag, "off" storing it back, "on" enqueueing and
     * "off" cancelling leaves the store reading on with the work cancelled, which is exactly
     * the state [SetAutoBackupEnabledUseCase] exists to make unreachable. A fair mutex makes
     * the second tap wait for the first and win, which is what a user tapping twice means.
     */
    private val autoBackupWrites = Mutex()

    val uiState: StateFlow<BackupUiState> = combine(
        localDataRepository.observeFootprint(),
        autoBackupRepository.observe(),
        screenState,
    ) { footprint, autoBackup, screen ->
        BackupUiState(
            footprint = footprint,
            phase = screen.phase,
            // Straight from the store, never mirrored into `ScreenState`: the switch has to
            // read what a worker running with no screen alive wrote, and a local copy would
            // draw the value this composition last set instead.
            autoBackup = autoBackup,
            autoBackupSaveFailed = screen.autoBackupSaveFailed,
            pendingRestoreUri = screen.pendingRestoreUri,
            lastExport = screen.lastExport,
            lastRestore = screen.lastRestore,
            lastSave = screen.lastSave,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = BackupUiState(),
    )

    fun onEvent(event: BackupEvent) {
        when (event) {
            is BackupEvent.ExportRequested -> export(event.appVersion)

            is BackupEvent.RestoreFileChosen -> screenState.update { it.copy(pendingRestoreUri = event.uri) }

            BackupEvent.RestoreDismissed -> screenState.update { it.copy(pendingRestoreUri = null) }

            BackupEvent.RestoreConfirmed -> restore()

            is BackupEvent.AutoBackupToggled -> setAutoBackup(event.enabled)

            is BackupEvent.SaveDestinationChosen -> saveCopy(event.uri)

            BackupEvent.OutcomeDismissed -> screenState.update {
                it.copy(lastExport = null, lastRestore = null, lastSave = null)
            }
        }
    }

    /**
     * Writes the whole device into a new file under [backupDirectory].
     *
     * Nothing is handed anywhere afterwards: the outcome names the file and the screen offers
     * to copy or share it. That is deliberate — an export that opened a share sheet by itself
     * put a chooser in front of a user whose next tap was usually "save this somewhere I can
     * find it", which the sheet on most devices does not even offer.
     */
    private fun export(appVersion: String) {
        if (screenState.value.phase != BackupPhase.Idle) return
        screenState.update {
            it.copy(
                phase = BackupPhase.Exporting(0, 0),
                lastExport = null,
                lastRestore = null,
                lastSave = null,
            )
        }

        viewModelScope.launch {
            // `runCatching` rather than a `catch (Throwable)`, which is the same breadth
            // written as an expression: every way a file write fails means one thing to the
            // user, and narrowing it would let a storage failure this build has not thought of
            // reach them as a crash. Cancellation is re-thrown, as it must be — a cancelled
            // `viewModelScope` is the screen going away, not an export that failed.
            val outcome = runCatching { writeExport(appVersion) }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                ExportOutcome.Failed
            }
            screenState.update { it.copy(phase = BackupPhase.Idle, lastExport = outcome) }
        }
    }

    /** The export itself: scratch file, stream, rename. Throws on any storage failure. */
    private suspend fun writeExport(appVersion: String): ExportOutcome {
        val destination = File(backupDirectory(), fileName())
        val scratch = File(destination.path + SCRATCH_SUFFIX)

        return withContext(Dispatchers.IO) {
            var rowsWritten = 0L
            runCatching {
                scratch.parentFile?.mkdirs()
                scratch.bufferedWriter().use { writer ->
                    exportBackup(ExportSink { chunk -> writer.write(chunk) }, appVersion).collect { progress ->
                        rowsWritten = progress.rowsWritten
                        screenState.update {
                            it.copy(
                                phase = BackupPhase.Exporting(
                                    rowsWritten = progress.rowsWritten,
                                    rowsTotal = progress.rowsTotal,
                                ),
                            )
                        }
                    }
                }
                if (!scratch.renameTo(destination)) throw IOException("could not move ${scratch.name} into place")
                ExportOutcome.Succeeded(fileName = destination.name, rowsWritten = rowsWritten)
            }.onFailure {
                // A truncated document is worse than no document: somebody would restore from
                // it. Best-effort — if the delete fails the file keeps its `.tmp` name, which
                // nothing offers to share and no picker filtered to JSON will show.
                scratch.delete()
            }.getOrThrow()
        }
    }

    /**
     * Turns the weekly automatic backup on or off.
     *
     * Not guarded on [BackupUiState.isBusy], unlike the other three: this writes one flag and
     * enqueues a job, so it neither reads the rows an export is reading nor touches the file a
     * copy is writing, and there is no reason to make somebody wait out a 30 MB export before
     * they may change a setting.
     *
     * A store that cannot be written throws, and the switch snaps back on its own because it is
     * drawn from the stored flag. The flag on the state is what turns that into an explanation
     * rather than a control that appears to ignore a tap; it is cleared by the next attempt, so
     * a retry that works takes the sentence away with it.
     */
    private fun setAutoBackup(enabled: Boolean) {
        screenState.update { it.copy(autoBackupSaveFailed = false) }

        viewModelScope.launch {
            // Broad for the reason the export's is: `IOException` is what the store documents,
            // but a scheduler that refuses the work is a platform failure of a shape this build
            // cannot enumerate, and both leave the user looking at the same unchanged switch.
            //
            // On IO, not on the main thread: the first `schedule()` of a process is what
            // initialises WorkManager, which opens its own database — a control that is
            // reachable mid-export has no business blocking the frame that draws it.
            val failed = runCatching {
                autoBackupWrites.withLock { withContext(Dispatchers.IO) { setAutoBackupEnabled(enabled) } }
            }.fold(
                onSuccess = { false },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    true
                },
            )
            screenState.update { it.copy(autoBackupSaveFailed = failed) }
        }
    }

    /**
     * Copies the last export to the document the user picked, byte for byte.
     *
     * ### Why this exists at all
     *
     * [backupDirectory] is app-specific external storage, which no file manager may browse on
     * Android 11 and up, and the share sheet lists apps that *receive* a file rather than
     * places to put one — on a stock device nothing in it offers "save to Downloads". So an
     * export the user could not reach was the whole of what the feature delivered. The system
     * document picker is the way out, and it needs no permission: `WRITE_EXTERNAL_STORAGE` has
     * been a no-op since Android 10, and the URI arrives already writable.
     *
     * ### A copy rather than a redirect
     *
     * The export still writes to its own directory first and this copies from there, rather
     * than streaming into the chosen URI directly. Two reasons: the scratch-file-and-rename
     * that keeps a failed export from leaving a restorable half-document has no equivalent on
     * a URI somebody else's provider owns, and a copy that fails costs the user a second tap
     * instead of the whole export. It also leaves the file in one place for [shareBackup].
     */
    private fun saveCopy(destination: String) {
        val fileName = (screenState.value.lastExport as? ExportOutcome.Succeeded)?.fileName ?: return
        if (screenState.value.phase != BackupPhase.Idle) return
        screenState.update { it.copy(phase = BackupPhase.Saving, lastSave = null) }

        viewModelScope.launch {
            // Broad for the reason the export's is: a destination the user chose through a
            // picker can fail in ways `IOException` does not cover — a provider that has gone
            // away between the pick and the write, a grant revoked — and all of them mean the
            // same "that did not work, choose somewhere else".
            val outcome = runCatching {
                withContext(Dispatchers.IO) { copyInto(File(backupDirectory(), fileName), Uri.parse(destination)) }
                SaveOutcome.Succeeded
            }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                SaveOutcome.Failed
            }
            screenState.update { it.copy(phase = BackupPhase.Idle, lastSave = outcome) }
        }
    }

    /**
     * Streams [source] into [destination], truncating whatever the picker created there.
     *
     * `copyTo` rather than `readBytes`, and for the reason [ImportSource] exists: the file may
     * be tens of megabytes, and a phone drawing a progress bar should never hold a training
     * history in a byte array to move it eight inches.
     *
     * The mode is `"wt"` and the `t` is the whole point. Plain `"w"` opens for writing *without
     * truncating* — `ContentResolver` maps it to `MODE_WRITE_ONLY or MODE_CREATE` and nothing
     * else — so overwriting a longer document with a shorter one leaves the old tail past the
     * end of the new one. The result is a file that is not JSON, produced by a save the user
     * was told had succeeded, and found only when a restore refuses it. `BackupViewModelTest`
     * pins it.
     */
    private fun copyInto(
        source: File,
        destination: Uri,
    ) {
        val sink = context.contentResolver.openOutputStream(destination, TRUNCATING_WRITE)
            ?: throw IOException("nothing to write at $destination")
        sink.use { output -> source.inputStream().use { input -> input.copyTo(output) } }
    }

    /**
     * Replaces everything on the device with the confirmed document.
     *
     * *Opening* the file is this class's job and `ImportUseCase`'s explicitly is not, so the
     * URI is handed over as an [ImportSource] the decoder re-opens for itself. An unreadable
     * one — revoked permission, a file deleted between the picker and the confirmation —
     * comes back as the [ImportError.IoFailure] the domain documents for exactly that case,
     * and anything the platform throws that is not an `IOException` is caught here and
     * reported as the same. Everything the *import* refuses arrives as a
     * [ImportProgress.Failed] emission instead, and is already typed.
     */
    private fun restore() {
        val uri = screenState.value.pendingRestoreUri ?: return
        if (screenState.value.phase != BackupPhase.Idle) return
        screenState.update {
            it.copy(
                phase = BackupPhase.Validating,
                pendingRestoreUri = null,
                lastExport = null,
                lastRestore = null,
                lastSave = null,
            )
        }

        viewModelScope.launch {
            // `Dispatchers.IO` around the *whole run* rather than around the file read alone:
            // `ImportUseCase` adds no dispatcher of its own, so parsing tens of megabytes of
            // JSON and checking every foreign key in it would otherwise happen on Main — the
            // choice `ImportSource` says #38 owes. `screenState` is a `MutableStateFlow`, so
            // mirroring progress back out of here is safe from any thread.
            //
            // The catch is broad on purpose, and for the reason the export's is: opening a
            // document the user chose can fail in ways a `catch (IOException)` does not cover —
            // a revoked permission, a provider that has gone away — and every one of them means
            // the same thing to them. What is *not* caught here is anything the import itself
            // refuses: `ImportUseCase` never throws those, it emits them, already typed.
            val outcome = runCatching {
                withContext(Dispatchers.IO) { runImport(documentAt(uri)) }
            }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                RestoreOutcome.Failed(ImportError.IoFailure(failure.message.orEmpty()))
            }
            screenState.update { it.copy(phase = BackupPhase.Idle, lastRestore = outcome) }
        }
    }

    /** Collects the import, mirroring its progress into [BackupPhase] and keeping the terminal one. */
    private suspend fun runImport(document: ImportSource): RestoreOutcome {
        var terminal: ImportProgress? = null
        importBackup(document).collect { progress ->
            terminal = progress
            progress.toPhase()?.let { phase -> screenState.update { it.copy(phase = phase) } }
        }
        return when (val last = terminal) {
            is ImportProgress.Complete -> RestoreOutcome.Succeeded(rowsRestored = last.rowsWritten)

            is ImportProgress.Failed -> RestoreOutcome.Failed(last.error)

            // Unreachable through `ImportUseCase`, which always ends on one of the two above.
            // Reported rather than ignored, because the alternative is a restore that says
            // nothing happened and leaves the user unable to tell whether it did.
            else -> RestoreOutcome.Failed(ImportError.IoFailure("the import ended without an outcome"))
        }
    }

    /**
     * The chosen document as an [ImportSource]: a *re-openable* handle, never its text.
     *
     * [ImportSource.open] is called more than once per import — the decoder reads the version
     * and then the document — so this hands back a new stream over the same content URI each
     * time rather than a cached `String`. That is also what keeps a 30 MB backup off the heap:
     * a `String` of it would be 60 MB of UTF-16 before a row had been parsed.
     *
     * A URI that resolves to nothing throws, which `ExportFormat.decode` already reports as
     * [ImportError.IoFailure] with the database untouched.
     */
    private fun documentAt(uri: String): ImportSource {
        val document = Uri.parse(uri)
        return ImportSource {
            context.contentResolver.openInputStream(document) ?: throw IOException("nothing to read at $uri")
        }
    }

    /**
     * Where backups are written: `backups/` under the app's own external files directory.
     *
     * App-specific storage, so it needs no permission, is listed under the app in the system's
     * storage screen, and goes away with an uninstall — which is the right lifetime for a file
     * whose whole purpose is to be handed to somewhere else. It falls back to internal storage
     * on a device with no external volume mounted, where `getExternalFilesDir` is null; a
     * `FileProvider` path is declared for both.
     */
    private fun backupDirectory(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, BACKUP_DIRECTORY)

    /**
     * `opengym-backup-2026-09-06-1432.json`.
     *
     * To the minute rather than the day, so two backups taken in one afternoon do not collide
     * in the share target — a file name is the only thing distinguishing them once they are
     * attached to a message. The device's own zone, not UTC: the name is read by a human who
     * knows when they pressed the button.
     */
    private fun fileName(): String {
        val stamp = FILE_NAME_FORMAT.format(Instant.ofEpochMilli(clock.nowMillis()).atZone(ZoneId.systemDefault()))
        return "$FILE_NAME_PREFIX$stamp$FILE_NAME_SUFFIX"
    }

    /** The half of the state that belongs to the screen rather than to a store. */
    private data class ScreenState(
        val phase: BackupPhase = BackupPhase.Idle,
        val autoBackupSaveFailed: Boolean = false,
        val pendingRestoreUri: String? = null,
        val lastExport: ExportOutcome? = null,
        val lastRestore: RestoreOutcome? = null,
        val lastSave: SaveOutcome? = null,
    )

    private companion object {

        val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds

        const val BACKUP_DIRECTORY = "backups"
        const val FILE_NAME_PREFIX = "opengym-backup-"
        const val FILE_NAME_SUFFIX = ".json"

        /** `w` opens without truncating; the `t` is what replaces a document rather than overwriting its front. */
        const val TRUNCATING_WRITE = "wt"

        /**
         * Not `.json.tmp`: nothing offers a file under this name to the share sheet, and the
         * restore picker sorts by the name a user recognises. The document itself is the real
         * guard — a truncated one fails to decode before a row is written — but a half-backup
         * that does not look like a backup is one fewer thing to pick by mistake.
         */
        const val SCRATCH_SUFFIX = ".tmp"

        val FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    }
}

/**
 * The phase an import emission describes, or **null** for the two that end the run.
 *
 * Null rather than an `Idle`, so the caller leaves the bar where the last write left it and
 * replaces it with the outcome in one update. Snapping it back to empty for a frame first
 * would read as the restore having been undone.
 */
private fun ImportProgress.toPhase(): BackupPhase? =
    when (this) {
        ImportProgress.Validating -> BackupPhase.Validating
        is ImportProgress.Writing -> BackupPhase.Importing(rowsWritten = rowsWritten, rowsTotal = rowsTotal)
        is ImportProgress.Complete, is ImportProgress.Failed -> null
    }
