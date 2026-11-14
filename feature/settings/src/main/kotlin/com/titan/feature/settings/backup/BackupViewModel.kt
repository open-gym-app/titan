package com.titan.feature.settings.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.core.domain.backup.BackupDirectory
import com.titan.core.domain.backup.ExportSink
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.backup.model.ImportProgress
import com.titan.core.domain.repository.AutoBackupRepository
import com.titan.core.domain.repository.LocalDataRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.ExportUseCase
import com.titan.core.domain.usecase.ImportUseCase
import com.titan.core.domain.usecase.SetAutoBackupEnabledUseCase
import com.titan.core.ui.state.savedScreenState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val backupDirectory: BackupDirectory,
    private val exportBackup: ExportUseCase,
    private val importBackup: ImportUseCase,
    private val setAutoBackupEnabled: SetAutoBackupEnabledUseCase,
    private val clock: Clock,
    localDataRepository: LocalDataRepository,
    autoBackupRepository: AutoBackupRepository,
) : ViewModel() {

    /**
     * What the screen owns, as opposed to what the store does.
     *
     * Saved for one field's sake (#183): [ScreenState.pendingRestoreUri], the file the user has
     * picked and not yet confirmed replacing their data with. It is a document the system picker
     * handed over, and losing it means going back through the picker to find it again. Nothing
     * else here survives — see the state class.
     */
    private val screenState = savedStateHandle.savedScreenState(viewModelScope) { ScreenState() }

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
            pendingRestoreName = screen.pendingRestoreName,
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

            is BackupEvent.RestoreFileChosen -> choose(event.uri)

            BackupEvent.RestoreDismissed -> screenState.update {
                it.copy(pendingRestoreUri = null, pendingRestoreName = null)
            }

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
        val name = fileName()

        return withContext(Dispatchers.IO) {
            // Resolved here rather than before the switch to IO: the first resolution in a
            // process may move an older install's backups into place (see [BackupDirectory]).
            val destination = File(backupDirectory.resolve(), name)
            val scratch = File(destination.path + SCRATCH_SUFFIX)
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
     * [backupDirectory] is app-specific storage: internal storage below Android 10, which no
     * file manager can browse at all, and the external files directory from it, which none
     * may browse on Android 11 and up. The share sheet lists apps that *receive* a file rather
     * than places to put one — on a stock device nothing in it offers "save to Downloads". So an
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
                withContext(Dispatchers.IO) { copyInto(exportedFile(fileName), Uri.parse(destination)) }
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
     * Holds [uri] for the confirmation, or refuses it if it is not somewhere a backup can come
     * from — see [isRestorable].
     *
     * The URI is held at once, so a confirmation that arrives straight after the pick finds it.
     * Everything that has to ask another process follows on `Dispatchers.IO`: the package
     * manager, for whether this app serves the authority, and the provider, for the document's
     * name. Neither belongs on the main thread, and the dialog draws without the name until
     * then. What can be decided from the URI's text alone — a `file://` path, an authority under
     * the application id — is refused here before the dialog ever opens.
     *
     * A confirmation that lands before the package manager has answered is not a way past it:
     * [documentAt] runs the whole check again before anything is read.
     */
    private fun choose(uri: String) {
        val document = Uri.parse(uri)
        if (!isRestorableByName(document)) {
            screenState.update { it.refusing(uri) }
            return
        }
        screenState.update { it.copy(pendingRestoreUri = uri, pendingRestoreName = null) }

        viewModelScope.launch {
            if (withContext(Dispatchers.IO) { isServedByThisApp(document) }) {
                // Only the pick it was asked about, and only while it is still waiting for the
                // user: once confirmed, [documentAt] refuses it on its own.
                screenState.update { if (it.pendingRestoreUri == uri) it.refusing(uri) else it }
                return@launch
            }
            // Broad for the reason every read of a picked document is: a provider can fail in
            // ways `IOException` does not cover, and a name the dialog cannot show costs the
            // line naming the file and nothing else.
            val name = runCatching { withContext(Dispatchers.IO) { displayNameOf(document) } }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                null
            }
            // Only onto the pick it was asked for: a second file chosen while the provider was
            // answering about the first must not be shown under the first one's name.
            screenState.update { if (it.pendingRestoreUri == uri) it.copy(pendingRestoreName = name) else it }
        }
    }

    /** This state with [uri] refused as a place to restore from, and no confirmation open. */
    private fun ScreenState.refusing(uri: String) =
        copy(
            pendingRestoreUri = null,
            pendingRestoreName = null,
            lastExport = null,
            lastRestore = RestoreOutcome.Failed(ImportError.IoFailure(refusedLocation(uri))),
            lastSave = null,
        )

    /**
     * Whether [document] is a place a restore may read from: a `content://` URI that another
     * app's provider serves.
     *
     * The picker only ever hands back such a URI, so this refuses nothing a user can choose. It
     * is here because the URI is also read back from saved state, and because what it guards is
     * a restore *replacing every row on the device* with whatever the bytes say:
     *
     * - **Not `file://`.** A path is read with this app's own permissions, which reach its
     *   private files — and no picker produces one, so nothing a user does needs it.
     * - **Not this app's own authority.** The app's `FileProvider` serves `backups/` and its
     *   other providers serve its internals; a URI naming one of them is the app reading
     *   itself through a door meant for other apps. That covers every authority under the
     *   application id, which is how all of them are declared, and anything the package
     *   manager resolves to this package besides.
     *
     * The second half asks the package manager, a call into the system process, so this is
     * for `Dispatchers.IO`; [choose] runs its two halves apart for that reason.
     */
    private fun isRestorable(document: Uri): Boolean = isRestorableByName(document) && !isServedByThisApp(document)

    /**
     * The half of [isRestorable] the URI's text decides: a `content://` scheme and an authority
     * that is not under the application id.
     *
     * The authority is compared without the `<user>@` prefix a cross-profile content URI
     * carries, which would otherwise hide the app's own name behind a number.
     */
    private fun isRestorableByName(document: Uri): Boolean {
        if (document.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = authorityOf(document) ?: return false
        val own = context.packageName
        return authority != own && !authority.startsWith("$own.")
    }

    /**
     * The half of [isRestorable] only the package manager can answer: whether the provider
     * behind [document]'s authority belongs to this app. A binder call; never on Main.
     */
    private fun isServedByThisApp(document: Uri): Boolean {
        val authority = authorityOf(document) ?: return false
        val provider = runCatching { context.packageManager.resolveContentProvider(authority, 0) }.getOrNull()
        return provider?.packageName == context.packageName
    }

    /** [document]'s authority without a cross-profile `<user>@` prefix, or null if it has none. */
    private fun authorityOf(document: Uri): String? =
        document.authority?.substringAfterLast('@')?.takeIf { it.isNotEmpty() }

    /**
     * What the provider calls [document], made safe to draw — or its last path segment if the
     * provider does not say, or null if neither leaves anything to show.
     */
    private fun displayNameOf(document: Uri): String? {
        val provided = context.contentResolver
            .query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return displayableDocumentName(provided ?: document.lastPathSegment)
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
                // Kept while the restore runs: the dialog stays up, inert, until it is over, and
                // it goes on naming the file it is replacing everything with.
                lastExport = null,
                lastRestore = null,
                lastSave = null,
            )
        }

        viewModelScope.launch {
            // `Dispatchers.IO` around the *whole run* rather than around the file read alone:
            // `ImportUseCase` adds no dispatcher of its own, so parsing tens of megabytes of
            // JSON and checking every foreign key in it would otherwise happen on Main — the
            // choice `ImportSource` leaves to its caller. `screenState` is a `MutableStateFlow`,
            // so mirroring progress back out of here is safe from any thread.
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
            screenState.update { it.copy(phase = BackupPhase.Idle, pendingRestoreName = null, lastRestore = outcome) }
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
     * [ImportError.IoFailure] with the database untouched. So does one [isRestorable] refuses —
     * checked again here rather than trusted from [choose], because the URI may have come back
     * from saved state rather than from the picker.
     */
    private fun documentAt(uri: String): ImportSource {
        val document = Uri.parse(uri)
        if (!isRestorable(document)) throw IOException(refusedLocation(uri))
        return ImportSource {
            context.contentResolver.openInputStream(document) ?: throw IOException("nothing to read at $uri")
        }
    }

    /**
     * The export called [fileName], in the directory every backup is written to.
     *
     * Public for the share, which the route hands straight to the platform: the screen knows
     * the file by the name on its card, and this is what turns that into the file. The
     * directory is [backupDirectory]'s, the one the weekly backup and "Delete all data" use
     * too — app-specific storage kept out of other apps' reach (see `AppBackupDirectory` for
     * the one open question on Android 10), and that goes away with an uninstall, which is the
     * right lifetime for a file whose whole purpose is to be handed to somewhere else.
     * `file_paths.xml` declares a `FileProvider` path under both roots it can be in.
     *
     * Looked for under every root rather than only the one [BackupDirectory.resolve] names now:
     * from Android 10 that follows whichever volume is mounted, so an export written to the
     * internal fallback while the external volume was away would otherwise be shared, or
     * copied, as a file that is not there once the volume came back.
     *
     * Cheap on the main thread once an export has run in this process, which a share always
     * follows.
     */
    fun exportedFile(fileName: String): File =
        backupDirectory.everyDirectory().map { File(it, fileName) }.firstOrNull(File::isFile)
            ?: File(backupDirectory.resolve(), fileName)

    /**
     * `titan-backup-2026-09-06-1432.json`.
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

    /**
     * What the screen shows and what the store does not know about.
     *
     * Only [pendingRestoreUri] and its [pendingRestoreName] come back. A [phase] is work in
     * flight, and the process that was doing it is gone — a screen restored mid-export would
     * show a progress bar over nothing. The three outcomes are reports of something that
     * finished before the user put the phone down, and re-announcing "restored 4,182 rows"
     * twenty minutes later is the app describing an event rather than reporting one.
     *
     * The permission the URI carries is the caller's to have taken: the picker grants it for the
     * life of the process unless it was taken persistably, which is why what is saved is the URI
     * and the *confirmation* rather than a promise the read will still work. A refused read is
     * already a failure this screen reports.
     */
    @Serializable
    private data class ScreenState(
        @Transient
        val phase: BackupPhase = BackupPhase.Idle,
        @Transient
        val autoBackupSaveFailed: Boolean = false,
        val pendingRestoreUri: String? = null,
        val pendingRestoreName: String? = null,
        @Transient
        val lastExport: ExportOutcome? = null,
        @Transient
        val lastRestore: RestoreOutcome? = null,
        @Transient
        val lastSave: SaveOutcome? = null,
    )

    private companion object {

        /** The reason recorded for a location [isRestorable] refuses. For a log, never shown. */
        private fun refusedLocation(uri: String) = "not a document another app provides: $uri"

        private val SUBSCRIPTION_TIMEOUT_MS = 5.seconds.inWholeMilliseconds

        private const val FILE_NAME_PREFIX = "titan-backup-"
        private const val FILE_NAME_SUFFIX = ".json"

        /** `w` opens without truncating; the `t` is what replaces a document rather than overwriting its front. */
        private const val TRUNCATING_WRITE = "wt"

        /**
         * Appended to the finished name, so the scratch file is `titan-backup-<stamp>.json.tmp`:
         * not a `.json`, so nothing offers it to the share sheet and a restore picker filtered
         * to JSON does not show it. The document itself is the real guard — a truncated one
         * fails to decode before a row is written — but a half-backup that does not look like a
         * backup is one fewer thing to pick by mistake.
         */
        private const val SCRATCH_SUFFIX = ".tmp"

        private val FILE_NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
    }
}

/**
 * [name] as the restore confirmation may draw it, or null if nothing drawable is left.
 *
 * The name comes from another app's provider, so it is treated as text somebody else wrote.
 * Control characters and Unicode format characters go — the second include the
 * right-to-left override that makes a name ending in `.exe` draw as one ending in `.json` — and
 * a name longer than [MAX_DOCUMENT_NAME_LENGTH] is cut short with an ellipsis, so it cannot push
 * the warning off a dialog that has to be read. The cut never splits a surrogate pair.
 */
internal fun displayableDocumentName(name: String?): String? {
    // By code point rather than by `Char`: the format characters outside the Basic Multilingual
    // Plane — the invisible tag characters among them — are two `Char`s that each read as a
    // surrogate, and an unpaired surrogate is itself one of the characters that draws wrong.
    val visible = buildString {
        name.orEmpty().codePoints().forEach { codePoint ->
            if (Character.getType(codePoint).toByte() !in HIDDEN_CHARACTER_TYPES) appendCodePoint(codePoint)
        }
    }.trim()
    return when {
        visible.isEmpty() -> null
        visible.length <= MAX_DOCUMENT_NAME_LENGTH -> visible
        else -> visible.take(MAX_DOCUMENT_NAME_LENGTH - 1).removeSuffixIf { it.isHighSurrogate() }.trimEnd() + ELLIPSIS
    }
}

private fun String.removeSuffixIf(predicate: (Char) -> Boolean): String =
    if (isNotEmpty() && predicate(last())) dropLast(1) else this

/** Long enough for any name the export writes and most a user gives a copy; short enough to fit a dialog. */
internal const val MAX_DOCUMENT_NAME_LENGTH = 80

private const val ELLIPSIS = "…"

/**
 * Control, format, the two separators and unpaired surrogates: characters that draw nothing, or
 * draw something else.
 */
private val HIDDEN_CHARACTER_TYPES = setOf(
    Character.CONTROL,
    Character.FORMAT,
    Character.LINE_SEPARATOR,
    Character.PARAGRAPH_SEPARATOR,
    Character.SURROGATE,
)

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
