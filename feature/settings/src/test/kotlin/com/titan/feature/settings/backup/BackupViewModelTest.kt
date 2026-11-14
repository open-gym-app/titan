package com.titan.feature.settings.backup

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.titan.core.domain.backup.ExportFormat
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.backup.model.ImportError
import com.titan.core.domain.usecase.ExportUseCase
import com.titan.core.domain.usecase.ImportUseCase
import com.titan.core.domain.usecase.SetAutoBackupEnabledUseCase
import com.titan.feature.settings.afterProcessDeath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
 *
 * ### Clearing every ViewModel before Main is reset
 *
 * The same `Dispatchers.IO` outlives the test that started it. A `withContext(Dispatchers.IO)`
 * still in flight when `runTest` returns resumes onto `viewModelScope` — onto Main — from an IO
 * thread, and if that lands while [tearDownMainDispatcher] is resetting Main, the test fails
 * with "Dispatchers.Main is used concurrently with setting it" (it did, on CI). So every
 * ViewModel is built through [viewModels], and teardown clears the store and waits for each
 * cancelled scope to finish before Main is touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val backend = BackupTestBackend()
    private val viewModels = ViewModelStore()
    private val created = mutableListOf<BackupViewModel>()

    /** Another app's documents provider, which is where every restore below reads from. */
    private val documents: TestDocumentsProvider =
        Robolectric.setupContentProvider(TestDocumentsProvider::class.java, TestDocumentsProvider.AUTHORITY)

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        viewModels.clear()
        runBlocking { created.forEach { it.viewModelScope.coroutineContext.job.join() } }
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
            assertEquals("titan-backup-2026-09-06-1229.json", (outcome as ExportOutcome.Succeeded).fileName)
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

    // --- saving a copy -----------------------------------------------------

    /**
     * The point of the whole feature: the bytes reach a place the user picked.
     *
     * The export itself lands in app-specific external storage, which no file manager may
     * browse on Android 11 and up, so an export that is never copied out is a backup that
     * cannot be restored from anywhere.
     */
    @Test
    fun `a copy writes the exported document to the chosen destination`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            val exported = viewModel.uiState.first { it.lastExport != null }.lastExport
            val destination = File(temporaryFolder, "somewhere-the-user-can-find-it.json")

            viewModel.onEvent(BackupEvent.SaveDestinationChosen(Uri.fromFile(destination).toString()))
            val outcome = viewModel.uiState.first { it.lastSave != null }.lastSave

            assertEquals(SaveOutcome.Succeeded, outcome)
            assertEquals(
                backupFile((exported as ExportOutcome.Succeeded).fileName).readText(),
                destination.readText(),
            )
        }

    /** A shorter backup written over a longer one must not keep the tail of the old document. */
    @Test
    fun `a copy over an existing file truncates it`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            val destination = File(temporaryFolder, "already-there.json")
            destination.writeText("x".repeat(LONGER_THAN_ANY_BACKUP))

            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            val exported = viewModel.uiState.first { it.lastExport != null }.lastExport
            viewModel.onEvent(BackupEvent.SaveDestinationChosen(Uri.fromFile(destination).toString()))
            viewModel.uiState.first { it.lastSave != null }

            assertEquals(
                backupFile((exported as ExportOutcome.Succeeded).fileName).readText(),
                destination.readText(),
            )
        }

    /** An unwritable destination is the user's problem to re-pick, not a crash and not a lost export. */
    @Test
    fun `a copy that cannot be written reports a failure and leaves the export alone`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            val exported = viewModel.uiState.first { it.lastExport != null }.lastExport

            // A directory, which no stream can be opened onto — the same throw a provider
            // that has gone away between the pick and the write produces.
            viewModel.onEvent(BackupEvent.SaveDestinationChosen(Uri.fromFile(temporaryFolder).toString()))
            val state = viewModel.uiState.first { it.lastSave != null }

            assertEquals(SaveOutcome.Failed, state.lastSave)
            assertEquals(exported, state.lastExport)
            assertEquals(BackupPhase.Idle, state.phase)
        }

    /** Nothing to copy before there has been an export, and a destination alone is not one. */
    @Test
    fun `a destination picked before any export copies nothing`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            val destination = File(temporaryFolder, "nothing-to-put-here.json")
            viewModel.onEvent(BackupEvent.SaveDestinationChosen(Uri.fromFile(destination).toString()))

            assertNull(viewModel.uiState.value.lastSave)
            assertEquals(false, destination.exists())
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

    // --- where a restore may read from (#257) --------------------------------

    /** The confirmation names the file, in the provider's words, once the provider has said. */
    @Test
    fun `a picked document is named in the confirmation`() =
        runTest {
            val file = File(backupDirectory().apply { mkdirs() }, "any.json").apply { writeText("{}") }
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(
                BackupEvent.RestoreFileChosen(documents.serve(file, "titan-backup-2026-09-06.json").toString()),
            )

            val named = viewModel.uiState.first { it.pendingRestoreName != null }
            assertEquals("titan-backup-2026-09-06.json", named.pendingRestoreName)
        }

    /** A provider that does not say is named by the last segment of its URI, rather than not at all. */
    @Test
    fun `a document whose provider gives no name is named by its URI`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.RestoreFileChosen(SOME_URI))

            assertEquals(
                "titan-backup.json",
                viewModel.uiState.first { it.pendingRestoreName != null }.pendingRestoreName,
            )
        }

    /** A path is read with the app's own permissions, and no picker hands one back. */
    @Test
    fun `a file path is refused without opening the confirmation`() =
        runTest {
            val file = File(backupDirectory().apply { mkdirs() }, "local.json").apply { writeText("{}") }
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.RestoreFileChosen(Uri.fromFile(file).toString()))

            assertRefusedLocation(viewModel)
        }

    /** The app's own provider serves its own files; a restore must not read the app through it. */
    @Test
    fun `a document the app's own provider serves is refused`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(
                BackupEvent.RestoreFileChosen("content://${context.packageName}.fileprovider/backups/x.json"),
            )

            assertRefusedLocation(viewModel)
        }

    /** A cross-profile URI puts a user id before the authority; it does not hide whose it is. */
    @Test
    fun `the app's own authority behind a user prefix is refused`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.RestoreFileChosen("content://10@${context.packageName}.fileprovider/x.json"))

            assertRefusedLocation(viewModel)
        }

    /**
     * An authority outside the application id that the package manager still resolves to this
     * app. Only the package manager can say so, and it is asked off the main thread, after the
     * confirmation has opened — so the refusal arrives late, and still closes it.
     */
    @Test
    fun `a provider of the app's own under another authority is refused`() =
        runTest {
            shadowOf(context.packageManager).addOrUpdateProvider(
                ProviderInfo().apply {
                    authority = ALIASED_AUTHORITY
                    packageName = context.packageName
                    name = "com.titan.AliasedProvider"
                },
            )
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.RestoreFileChosen("content://$ALIASED_AUTHORITY/backups/x.json"))

            assertRefusedLocation(viewModel)
        }

    /**
     * The URI comes back from saved state as well as from the picker, so the restore checks it
     * again rather than trusting the check at the pick.
     */
    @Test
    fun `a refused location restored from saved state is refused at the confirmation`() =
        runTest {
            backend.seedOneSession()
            val file = File(backupDirectory().apply { mkdirs() }, "local.json").apply { writeText("{}") }
            val served = documents.serve(file).toString()
            val savedState = SavedStateHandle()
            viewModel(savedState).onEvent(BackupEvent.RestoreFileChosen(served))
            val tampered = savedState.afterProcessDeath().apply {
                val key = keys().single()
                set(key, get<String>(key)!!.replace(served, Uri.fromFile(file).toString()))
            }

            val restored = viewModel(tampered)
            collect(restored)
            restored.onEvent(BackupEvent.RestoreConfirmed)
            val outcome = restored.uiState.first { it.lastRestore != null }.lastRestore

            assertTrue("was $outcome", (outcome as RestoreOutcome.Failed).error is ImportError.IoFailure)
            assertEquals(1, backend.sessionCount())
        }

    @Test
    fun `the picked name survives process death with the file`() =
        runTest {
            val file = File(backupDirectory().apply { mkdirs() }, "any.json").apply { writeText("{}") }
            val savedState = SavedStateHandle()
            val first = viewModel(savedState)
            collect(first)
            first.onEvent(BackupEvent.RestoreFileChosen(documents.serve(file, "mine.json").toString()))
            first.uiState.first { it.pendingRestoreName != null }

            val restored = viewModel(savedState.afterProcessDeath())
            collect(restored)

            assertEquals("mine.json", restored.uiState.first { it.pendingRestoreUri != null }.pendingRestoreName)
        }

    /** A restore resets the reminder with the settings, so the alarms are re-synced behind it. */
    @Test
    fun `a restore re-syncs the reminder alarms`() =
        runTest {
            backend.seedOneSession()
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            val exported = viewModel.uiState.first { it.lastExport != null }.lastExport as ExportOutcome.Succeeded

            restore(viewModel, backupFile(exported.fileName))
            viewModel.uiState.first { it.lastRestore != null }

            assertEquals(1, backend.reminders.syncs)
        }

    // --- the name a confirmation draws ------------------------------------

    @Test
    fun `a plain name is drawn as it is`() {
        assertEquals("titan-backup-2026-09-06-1432.json", displayableDocumentName("titan-backup-2026-09-06-1432.json"))
    }

    /** U+202E would draw `titan‮nosj.exe` as `titanexe.json`. */
    @Test
    fun `direction overrides and control characters are dropped`() {
        assertEquals("titannosj.exe", displayableDocumentName("titan\u202Enosj.exe"))
        assertEquals("ab.json", displayableDocumentName("a\u0000b\n.json"))
    }

    /** U+E0041 is an invisible tag character, two `Char`s that a filter by `Char` keeps. */
    @Test
    fun `format characters outside the basic plane and lone surrogates are dropped`() {
        assertEquals("ab.json", displayableDocumentName("a󠁁b\uD800.json"))
        assertEquals("💪.json", displayableDocumentName("💪.json"))
    }

    @Test
    fun `a name with nothing drawable left is no name`() {
        assertNull(displayableDocumentName("\u200E\u202E  "))
        assertNull(displayableDocumentName(null))
    }

    @Test
    fun `a long name is cut short with an ellipsis, never through a surrogate pair`() {
        val long = "a".repeat(MAX_DOCUMENT_NAME_LENGTH - 2) + "\uD83D\uDCAA" + "b".repeat(20)

        val drawn = displayableDocumentName(long)!!

        assertEquals(MAX_DOCUMENT_NAME_LENGTH - 1, drawn.length)
        assertTrue(drawn, drawn.endsWith("a…"))
    }

    @Test
    fun `dismissing an outcome clears both of them`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))
            viewModel.uiState.first { it.lastExport != null }

            viewModel.onEvent(BackupEvent.OutcomeDismissed)

            // Read back through the flow rather than `.value`: the export that set `lastExport`
            // finished on `Dispatchers.IO`, and `.value` can still be the `stateIn` cache from
            // just before that combine's own dispatch caught up with the dismissal.
            val dismissed = viewModel.uiState.first { it.lastExport == null && it.lastRestore == null }
            assertNull(dismissed.lastExport)
            assertNull(dismissed.lastRestore)
        }

    // --- the weekly automatic backup --------------------------------------

    /** The two halves the use case exists to keep together, seen from the screen's side. */
    @Test
    fun `turning the schedule on stores the flag and enqueues the work`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            viewModel.onEvent(BackupEvent.AutoBackupToggled(true))

            // The switch's own state and the scheduler call land in that order, but off two
            // different suspension points — see `RecordingAutoBackupScheduler` — so the count
            // is awaited on its own rather than read the instant the switch settles.
            assertTrue(viewModel.uiState.first { it.autoBackup.enabled }.autoBackup.enabled)
            backend.scheduler.awaitSchedule()
            assertEquals(1, backend.scheduler.scheduleCount)
        }

    @Test
    fun `turning it off cancels the work`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(BackupEvent.AutoBackupToggled(true))
            viewModel.uiState.first { it.autoBackup.enabled }
            backend.scheduler.awaitSchedule()

            viewModel.onEvent(BackupEvent.AutoBackupToggled(false))

            assertEquals(false, viewModel.uiState.first { !it.autoBackup.enabled }.autoBackup.enabled)
            backend.scheduler.awaitCancel()
            assertEquals(1, backend.scheduler.cancelCount)
        }

    /**
     * The row draws what the *store* holds, not what this composition last set.
     *
     * A worker records its run with no screen alive, so the timestamp on the row has to come
     * off the store on the next launch. Mirroring it into the ViewModel's own state would draw
     * a blank row on exactly the launch where it matters.
     */
    @Test
    fun `a run recorded by the worker reaches the state`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)

            backend.autoBackup.recordSuccess(LAST_BACKUP_AT)

            assertEquals(LAST_BACKUP_AT, viewModel.uiState.first { it.autoBackup.hasRun }.autoBackup.lastSuccessAt)
        }

    /** A switch that snaps back has to say why, and the explanation goes when a retry works. */
    @Test
    fun `a toggle that cannot be stored is reported and the schedule is left alone`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            backend.autoBackup.writesFail = true

            viewModel.onEvent(BackupEvent.AutoBackupToggled(true))
            val failed = viewModel.uiState.first { it.autoBackupSaveFailed }

            assertEquals(false, failed.autoBackup.enabled)
            assertEquals(0, backend.scheduler.scheduleCount)

            backend.autoBackup.writesFail = false
            viewModel.onEvent(BackupEvent.AutoBackupToggled(true))

            assertEquals(false, viewModel.uiState.first { it.autoBackup.enabled }.autoBackupSaveFailed)
        }

    /**
     * The one control on the screen that is not blocked by a running export.
     *
     * It writes one flag and enqueues a job — it reads no rows and touches no file — so making
     * somebody wait out a 30 MB export before they may change a setting would buy nothing.
     */
    @Test
    fun `the schedule can be changed while an export is running`() =
        runTest {
            val viewModel = viewModel()
            collect(viewModel)
            viewModel.onEvent(BackupEvent.ExportRequested(APP_VERSION))

            viewModel.onEvent(BackupEvent.AutoBackupToggled(true))

            assertTrue(viewModel.uiState.first { it.autoBackup.enabled }.autoBackup.enabled)
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

    /**
     * Picks [file] and confirms, which is what the dialog does once the word has been typed.
     *
     * Through [documents], because a restore reads only from another app's provider: the
     * picker's URI, never a path.
     */
    private fun restore(
        viewModel: BackupViewModel,
        file: File,
    ) {
        viewModel.onEvent(BackupEvent.RestoreFileChosen(documents.serve(file).toString()))
        viewModel.onEvent(BackupEvent.RestoreConfirmed)
    }

    /** No confirmation, and a failure that says the file could not be read. */
    private suspend fun assertRefusedLocation(viewModel: BackupViewModel) {
        val state = viewModel.uiState.first { it.lastRestore != null }
        assertNull("no confirmation should open", state.pendingRestoreUri)
        assertTrue(
            "was ${state.lastRestore}",
            (state.lastRestore as RestoreOutcome.Failed).error is ImportError.IoFailure,
        )
        assertEquals(BackupPhase.Idle, state.phase)
    }

    private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()): BackupViewModel {
        val factory = viewModelFactory { initializer { newViewModel(savedState) } }
        return ViewModelProvider.create(viewModels, factory)["backup-${created.size}", BackupViewModel::class]
            .also { created += it }
    }

    private fun newViewModel(savedState: SavedStateHandle) =
        BackupViewModel(
            savedStateHandle = savedState,
            context = context,
            exportBackup = ExportUseCase(
                backupRepository = backend.backupRepository,
                userPreferencesRepository = backend.preferences,
            ),
            importBackup = ImportUseCase(
                backupRepository = backend.backupRepository,
                userPreferencesRepository = backend.preferences,
                inProgressSessionRepository = backend.draft,
                templateDraftRepository = backend.templateDraft,
                sessionStateRepository = backend.sessionState,
                reminderScheduler = backend.reminders,
            ),
            setAutoBackupEnabled = SetAutoBackupEnabledUseCase(
                autoBackupRepository = backend.autoBackup,
                scheduler = backend.scheduler,
            ),
            clock = { EXPORTED_AT },
            localDataRepository = backend.localDataRepository,
            autoBackupRepository = backend.autoBackup,
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

    /**
     * Somewhere to copy *to* that is not [backupDirectory].
     *
     * `cacheDir` rather than the backup directory, because a test asserting that a copy landed
     * where the user pointed it should not be able to pass by reading the file it started from.
     */
    private val temporaryFolder: File get() = context.cacheDir

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

        const val SOME_URI = "content://downloads/titan-backup.json"

        /** Not under the application id, for a provider the package manager says is this app's. */
        const val ALIASED_AUTHORITY = "org.example.titan-alias"

        /** 2026-04-26, a day the auto-backup row can name. */
        const val LAST_BACKUP_AT = 1_777_161_600_000L

        /** Bigger than the document a seeded backend exports, so a truncation is visible. */
        const val LONGER_THAN_ANY_BACKUP = 100_000
    }
}
