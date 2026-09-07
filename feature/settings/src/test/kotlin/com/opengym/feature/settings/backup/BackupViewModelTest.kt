package com.opengym.feature.settings.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.opengym.core.domain.backup.ExportFormat
import com.opengym.core.domain.backup.ImportSource
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.usecase.ExportUseCase
import com.opengym.core.domain.usecase.ImportUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The backup ViewModel over the real use cases and real files.
 *
 * ### Real use cases, real files, a faked store
 *
 * What this class is *for* is the part between the use cases and the platform — a scratch file
 * renamed into place, a content URI read back, an `ImportError` turned into an outcome — and a
 * fake export that wrote three bytes would exercise none of it. So `ExportUseCase` and
 * `ImportUseCase` are the real ones, and the bytes really go to a disk: Robolectric gives
 * `getExternalFilesDir` a temporary directory, and the restore reads them back through
 * `ContentResolver`.
 *
 * The *store* under them is faked, and has to be: a feature module may not depend on
 * `:core:data`. Whether the rows survive a round trip over a real SQLite is
 * `BackupRoundTripTest`'s question, in the module that owns the schema. See
 * [BackupTestBackend].
 *
 * ### Awaiting rather than advancing
 *
 * The blocking work is on `Dispatchers.IO`, which no test scheduler owns — see the note on
 * `BackupViewModel`. So every test collects `uiState` in the background and suspends on
 * `first { … }` until the state it expects arrives, which is what a screen does anyway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = BackupTestBackend()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    // --- export -----------------------------------------------------------

    @Test
    fun `an export writes a document that decodes back`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            val outcome = viewModel.uiState.first { it.lastExport != null }.lastExport

            val succeeded = outcome as ExportOutcome.Succeeded
            val document = ExportFormat.decode(source(backupFile(succeeded.fileName)))
            assertTrue(
                "the export should be a readable document, was $document",
                document is ExportFormat.Decoded.Success,
            )
            assertEquals(APP_VERSION, (document as ExportFormat.Decoded.Success).document.appVersion)
        }

    @Test
    fun `the file is named for the moment it was taken`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            val outcome = viewModel.uiState.first { it.lastExport != null }.lastExport

            // Tests run pinned to UTC — see the root build script — so the stamp is the clock's
            // own reading rather than a function of the machine.
            assertEquals("opengym-backup-2026-09-06-1229.json", (outcome as ExportOutcome.Succeeded).fileName)
        }

    /** The rows the exporter counted are the rows the outcome reports. */
    @Test
    fun `a finished export reports what it wrote`() =
        runTest {
            backend.seedOneSession()
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            val outcome = viewModel.uiState.first { it.lastExport != null }.lastExport

            assertTrue((outcome as ExportOutcome.Succeeded).rowsWritten > 0)
        }

    /** A completed export offers itself to the share sheet exactly once. */
    @Test
    fun `a finished export offers the file to share, and forgets it once handled`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            val shared = viewModel.uiState.first { it.share != null }.share

            assertEquals((viewModel.uiState.value.lastExport as ExportOutcome.Succeeded).fileName, shared)
            viewModel.onEvent(BackupEvent.ShareHandled)
            assertNull(viewModel.uiState.value.share)
        }

    /** No `.tmp` is left behind: a truncated document is something somebody would restore from. */
    @Test
    fun `a finished export leaves no scratch file`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            viewModel.uiState.first { it.lastExport != null }

            val leftovers = backupDirectory().listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
            assertEquals(emptyList<File>(), leftovers)
        }

    @Test
    fun `an export ends with the screen idle again`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            val state = viewModel.uiState.first { it.lastExport != null }

            assertEquals(BackupPhase.Idle, state.phase)
            assertTrue(!state.isBusy)
        }

    // --- restore ----------------------------------------------------------

    /** Picking a file arms the confirmation and writes nothing. */
    @Test
    fun `a picked file waits for the confirmation`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.RestoreFileChosen(SOME_URI))

            assertEquals(SOME_URI, viewModel.uiState.value.pendingRestoreUri)
            assertEquals(BackupPhase.Idle, viewModel.uiState.value.phase)
            assertNull(viewModel.uiState.value.lastRestore)
        }

    @Test
    fun `dismissing the confirmation forgets the file`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.RestoreFileChosen(SOME_URI))
            viewModel.onEvent(BackupEvent.RestoreDismissed)

            assertNull(viewModel.uiState.value.pendingRestoreUri)
            assertNull(viewModel.uiState.value.lastRestore)
        }

    /** Confirming with nothing picked does nothing — the guard behind the dialog's own guard. */
    @Test
    fun `confirming with no file picked does nothing`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.RestoreConfirmed)

            assertEquals(BackupPhase.Idle, viewModel.uiState.value.phase)
            assertNull(viewModel.uiState.value.lastRestore)
        }

    /** The whole loop: export this device, wipe it, restore the file it wrote. */
    @Test
    fun `a device restores from a file it exported itself`() =
        runTest {
            backend.seedOneSession()
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            val exported = viewModel.uiState.first { it.lastExport != null }.lastExport as ExportOutcome.Succeeded
            backend.wipe()

            restore(viewModel, backupFile(exported.fileName))
            val outcome = viewModel.uiState.first { it.lastRestore != null }.lastRestore

            assertEquals(RestoreOutcome.Succeeded(rowsRestored = exported.rowsWritten), outcome)
            assertEquals(1, backend.sessionCount())
        }

    @Test
    fun `a document from a newer version is refused with its own error`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            val exported = viewModel.uiState.first { it.lastExport != null }.lastExport as ExportOutcome.Succeeded
            val file = backupFile(exported.fileName)
            file.writeText(file.readText().replace("\"schema_version\":1", "\"schema_version\":2"))

            restore(viewModel, file)
            val outcome = viewModel.uiState.first { it.lastRestore != null }.lastRestore

            assertEquals(
                RestoreOutcome.Failed(ImportError.UnsupportedSchemaVersion(found = 2, supported = 1)),
                outcome,
            )
        }

    @Test
    fun `a file that is not a backup is refused as malformed`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            val file = File(backupDirectory().apply { mkdirs() }, "not-a-backup.json")
            file.writeText("{ this is not a backup")

            restore(viewModel, file)
            val outcome = viewModel.uiState.first { it.lastRestore != null }.lastRestore

            assertTrue("was $outcome", (outcome as RestoreOutcome.Failed).error is ImportError.MalformedJson)
        }

    /** Reading the file is this class's job, so a URI that resolves to nothing is its failure. */
    @Test
    fun `a file that cannot be read is reported as an IO failure`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            restore(viewModel, File(backupDirectory(), "gone.json"))
            val outcome = viewModel.uiState.first { it.lastRestore != null }.lastRestore

            assertTrue("was $outcome", (outcome as RestoreOutcome.Failed).error is ImportError.IoFailure)
        }

    /** A refused restore leaves the device exactly as it was and the screen usable. */
    @Test
    fun `a refused restore changes nothing and leaves the screen idle`() =
        runTest {
            backend.seedOneSession()
            val viewModel = viewModel()
            collect(viewModel)
            val file = File(backupDirectory().apply { mkdirs() }, "broken.json")
            file.writeText("nonsense")

            restore(viewModel, file)
            val state = viewModel.uiState.first { it.lastRestore != null }

            assertEquals(1, backend.sessionCount())
            assertEquals(BackupPhase.Idle, state.phase)
            assertTrue(!state.isBusy)
        }

    @Test
    fun `dismissing an outcome clears both of them`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            viewModel.uiState.first { it.lastExport != null }

            viewModel.onEvent(BackupEvent.OutcomeDismissed)

            assertNull(viewModel.uiState.value.lastExport)
            assertNull(viewModel.uiState.value.lastRestore)
        }

    // --- the footprint ----------------------------------------------------

    @Test
    fun `the footprint is what the store says`() =
        runTest {
            backend.seedOneSession()
            val viewModel = viewModel()
            collect(viewModel)

            val footprint = viewModel.uiState.first { it.footprint.sessions > 0 }.footprint

            assertEquals(1, footprint.sessions)
            assertTrue("the database should have a measurable size", footprint.databaseBytes > 0)
            assertEquals(BackupTestBackend.SESSION_CREATED_AT, footprint.firstSessionAt)
        }

    // --- helpers ----------------------------------------------------------

    /** Picks [file] and confirms, which is what the dialog does once the word has been typed. */
    private fun restore(
        viewModel: BackupViewModel,
        file: File,
    ) {
        viewModel.onEvent(BackupEvent.RestoreFileChosen(Uri.fromFile(file).toString()))
        viewModel.onEvent(BackupEvent.RestoreConfirmed)
    }

    private fun viewModel() =
        BackupViewModel(
            context = context,
            exportBackup = ExportUseCase(
                backupRepository = backend.backupRepository,
                userPreferencesRepository = backend.preferences,
            ),
            importBackup = ImportUseCase(
                backupRepository = backend.backupRepository,
                userPreferencesRepository = backend.preferences,
                inProgressSessionRepository = backend.draft,
                sessionStateRepository = backend.sessionState,
            ),
            clock = { EXPORTED_AT },
            localDataRepository = backend.localDataRepository,
        )

    /**
     * `uiState` is a `stateIn(WhileSubscribed)`, so every test collects it in the background
     * first: without a collector the flow is not started and the state stays at its initial
     * value, which would make every assertion pass for the wrong reason.
     */
    private fun TestScope.collect(viewModel: BackupViewModel) {
        // Unconfined *on the test's own scheduler*, so the collection has started — and the
        // first `combine` emission landed — by the time the call that started it returns.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect { } }
    }

    private fun backupDirectory() = File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")

    private fun backupFile(name: String) = File(backupDirectory(), name)

    /**
     * [file] as an [ImportSource] — a handle that re-opens, which is what the decoder needs:
     * it reads the version off one stream and the document off a second.
     */
    private fun source(file: File) = ImportSource { file.inputStream() }

    private companion object {

        const val APP_VERSION = "1.4.2-debug"

        /** 2026-09-06 14:29 UTC, which is the stamp the file name is asserted against. */
        const val EXPORTED_AT = 1_788_697_774_000L

        const val SOME_URI = "content://downloads/opengym-backup.json"
    }
}
