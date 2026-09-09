package com.opengym.feature.settings.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.opengym.core.domain.backup.AutoBackupSchedule
import com.opengym.core.domain.backup.model.ImportError
import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.ui.theme.OpenGymTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The backup screen, driven stateless with hand-built states — no ViewModel, no Hilt, no
 * files. See CONTRIBUTING.md § Compose tests.
 *
 * Nodes are found by the text a user reads, as on the settings screen: a test that stops
 * finding a control is telling us its wording changed rather than that a test id was renamed.
 */
@RunWith(RobolectricTestRunner::class)
class BackupScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<BackupEvent>()
    private var pickerOpened = 0
    private val saved = mutableListOf<String>()
    private val shared = mutableListOf<String>()

    // --- what is on the device --------------------------------------------

    @Test
    fun `the footprint card shows the counts, the start date and the size`() {
        setContent(footprint = FOOTPRINT)

        // `StatTile` speaks as one node — a figure and its label read separately are two
        // announcements for one fact — so the tiles are found by what they say, not by the
        // digits drawn in them.
        composeTestRule.onNodeWithContentDescription("Sessions, 184").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Sets, 2,140").assertIsDisplayed()
        composeTestRule.onNodeWithText("Training since April 2024", substring = true).assertIsDisplayed()
    }

    /** A device with no history says so rather than dating itself to 1970. */
    @Test
    fun `a device that has logged nothing says so instead of showing a date`() {
        setContent(footprint = DataFootprint())

        composeTestRule.onNodeWithText("Nothing logged yet", substring = true).assertIsDisplayed()
    }

    // --- export -----------------------------------------------------------

    @Test
    fun `the export button reports the running build's version`() {
        setContent(appVersion = "1.4.2")

        composeTestRule.onNodeWithText("Export a backup").performScrollTo().performClick()

        assertEquals(listOf(BackupEvent.ExportRequested("1.4.2")), events)
    }

    @Test
    fun `an export in flight shows a determinate bar and holds both buttons inert`() {
        setContent(phase = BackupPhase.Exporting(rowsWritten = 26_000, rowsTotal = 52_000))

        // The bar and its caption speak as one node, so the progress is found by what it
        // announces — which is also the assertion that it is *determinate*: an indeterminate
        // one has no percentage to say.
        composeTestRule.onNodeWithContentDescription("Exporting… 50%").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Export a backup").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Choose a backup file").assertIsNotEnabled()
    }

    @Test
    fun `a finished export names the file it wrote`() {
        setContent(lastExport = EXPORTED)

        composeTestRule
            .onNodeWithText("opengym-backup-2026-09-06-1432.json", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /**
     * The fix for a backup nobody could reach: the export lands in app-specific storage, which
     * no file manager may browse, so the card has to offer a way out to a real folder.
     */
    @Test
    fun `a finished export offers to save a copy of the file it named`() {
        setContent(lastExport = EXPORTED)

        composeTestRule.onNodeWithText("Save a copy…").performScrollTo().performClick()

        assertEquals(listOf("opengym-backup-2026-09-06-1432.json"), saved)
        assertEquals(emptyList<BackupEvent>(), events)
    }

    /** Sharing is the platform's, not the ViewModel's: the screen reports nothing when it happens. */
    @Test
    fun `a finished export offers to share the file it named`() {
        setContent(lastExport = EXPORTED)

        composeTestRule.onNodeWithText("Share").performScrollTo().performClick()

        assertEquals(listOf("opengym-backup-2026-09-06-1432.json"), shared)
        assertEquals(emptyList<BackupEvent>(), events)
    }

    /** Nothing is offered until there is a file: both actions are about a named export. */
    @Test
    fun `an export that has not happened offers neither action`() {
        setContent(footprint = FOOTPRINT)

        assertTrue(
            "the copy and share actions belong to a finished export",
            composeTestRule.onAllNodesWithText("Save a copy…").fetchSemanticsNodes().isEmpty() &&
                composeTestRule.onAllNodesWithText("Share").fetchSemanticsNodes().isEmpty(),
        )
    }

    /** A second destination picked mid-copy would have two writes reading one file. */
    @Test
    fun `a copy in flight says so and holds the card's own buttons inert`() {
        setContent(phase = BackupPhase.Saving, lastExport = EXPORTED)

        // The bar and its caption speak as one node, as the export's do, so it is found by
        // what it announces rather than by the text drawn in it.
        composeTestRule.onNodeWithContentDescription("Saving a copy…").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Save a copy…").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Share").assertIsNotEnabled()
    }

    @Test
    fun `a saved copy says where it went in the user's terms`() {
        setContent(lastExport = EXPORTED, lastSave = SaveOutcome.Succeeded)

        composeTestRule.onNodeWithText("saved where you chose", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** A copy that failed must not read as an export that failed: the backup is still there. */
    @Test
    fun `a failed copy points at the destination rather than the backup`() {
        setContent(lastExport = EXPORTED, lastSave = SaveOutcome.Failed)

        composeTestRule.onNodeWithText("Choose another location", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("opengym-backup-2026-09-06-1432.json", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a failed export says so and leaves the buttons usable`() {
        setContent(lastExport = ExportOutcome.Failed)

        composeTestRule.onNodeWithText("could not be written", substring = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Export a backup").assertIsEnabled()
    }

    @Test
    fun `dismissing an outcome reports it`() {
        setContent(lastExport = ExportOutcome.Failed)

        composeTestRule.onNodeWithText("Dismiss").performScrollTo().performClick()

        assertEquals(listOf(BackupEvent.OutcomeDismissed), events)
    }

    // --- restore ----------------------------------------------------------

    /** The picker is launched by the route, so the screen only has to ask for it. */
    @Test
    fun `the restore button opens the file picker and starts nothing`() {
        setContent(footprint = FOOTPRINT)

        composeTestRule.onNodeWithText("Choose a backup file").performScrollTo().performClick()

        assertEquals(1, pickerOpened)
        assertEquals(emptyList<BackupEvent>(), events)
    }

    /**
     * The AC in full: a picked file opens the confirmation, the confirmation states the
     * replace semantics, and it shows what is on the device now.
     */
    @Test
    fun `a picked file opens a confirmation that states the replacement and its cost`() {
        setContent(footprint = FOOTPRINT, pendingRestoreUri = SOME_URI)

        composeTestRule.onNodeWithText("Replace everything?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Everything below is deleted", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("184").assertIsDisplayed()
        composeTestRule.onNodeWithText("2,140").assertIsDisplayed()
    }

    /** The whole point of the typed word: the destructive button is inert until it is typed. */
    @Test
    fun `the replace button is inert until the confirmation word is typed`() {
        setContent(footprint = FOOTPRINT, pendingRestoreUri = SOME_URI)

        composeTestRule.onNodeWithText("Replace").assertIsNotEnabled()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("RESTORE")

        composeTestRule.onNodeWithText("Replace").assertIsEnabled()
    }

    @Test
    fun `a wrong word leaves the replace button inert`() {
        setContent(footprint = FOOTPRINT, pendingRestoreUri = SOME_URI)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("DELETE")

        composeTestRule.onNodeWithText("Replace").assertIsNotEnabled()
        assertEquals(emptyList<BackupEvent>(), events)
    }

    @Test
    fun `the typed confirmation reports the restore`() {
        setContent(footprint = FOOTPRINT, pendingRestoreUri = SOME_URI)

        composeTestRule.onNode(hasSetTextAction()).performTextInput("restore")
        composeTestRule.onNodeWithText("Replace").performClick()

        assertEquals(listOf(BackupEvent.RestoreConfirmed), events)
    }

    @Test
    fun `cancelling the confirmation reports a dismissal and nothing else`() {
        setContent(footprint = FOOTPRINT, pendingRestoreUri = SOME_URI)

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf(BackupEvent.RestoreDismissed), events)
    }

    @Test
    fun `a restore in flight keeps the dialog up and inert`() {
        setContent(footprint = FOOTPRINT, phase = BackupPhase.Importing(rowsWritten = 100, rowsTotal = 52_000))

        composeTestRule.onNodeWithText("Restoring…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun `a finished restore says how much came back`() {
        setContent(lastRestore = RestoreOutcome.Succeeded(rowsRestored = 52_180))

        composeTestRule
            .onNodeWithText("Restored 52180 rows", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    // --- one message per failure -------------------------------------------

    /**
     * #38's fifth acceptance criterion, one test per case. "Import failed" for four different
     * causes is not an error state: a damaged file, a newer app, a truncated backup and an
     * unreadable one send the user four different places.
     */
    @Test
    fun `a malformed file has its own message`() {
        setContent(lastRestore = RestoreOutcome.Failed(ImportError.MalformedJson("Unexpected EOF")))

        composeTestRule.onNodeWithText("not an Open Gym backup", substring = true).performScrollTo().assertIsDisplayed()
        assertSaysNothingChanged()
    }

    @Test
    fun `an unsupported version names both versions`() {
        setContent(lastRestore = RestoreOutcome.Failed(ImportError.UnsupportedSchemaVersion(found = 2, supported = 1)))

        composeTestRule.onNodeWithText("version 2", substring = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("version 1", substring = true).assertIsDisplayed()
        assertSaysNothingChanged()
    }

    @Test
    fun `a constraint violation has its own message`() {
        setContent(
            lastRestore = RestoreOutcome.Failed(ImportError.ConstraintViolation("workout_set.session_exercise_id 9")),
        )

        composeTestRule.onNodeWithText("incomplete", substring = true).performScrollTo().assertIsDisplayed()
        assertSaysNothingChanged()
    }

    /** The one failure that may have changed something, and the only one that does not promise otherwise. */
    @Test
    fun `an IO failure has its own message and does not promise the data is untouched`() {
        setContent(lastRestore = RestoreOutcome.Failed(ImportError.IoFailure("read-only filesystem")))

        composeTestRule.onNodeWithText("could not be read", substring = true).performScrollTo().assertIsDisplayed()
        val promises = composeTestRule
            .onAllNodesWithText("Nothing on this device was changed", substring = true)
            .fetchSemanticsNodes()
        assertTrue("this failure must not claim the device is untouched", promises.isEmpty())
    }

    /** A failed restore leaves the screen usable: both actions come back. */
    @Test
    fun `a failed restore leaves both actions available`() {
        setContent(lastRestore = RestoreOutcome.Failed(ImportError.MalformedJson("bad")))

        composeTestRule.onNodeWithText("Export a backup").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithText("Choose a backup file").performScrollTo().assertIsEnabled()
    }

    /** No detail from the error object reaches the screen: a parser offset is for a log. */
    @Test
    fun `the technical reason is not shown to the user`() {
        setContent(lastRestore = RestoreOutcome.Failed(ImportError.MalformedJson("Unexpected EOF at offset 4211")))

        val leaked = composeTestRule.onAllNodesWithText("4211", substring = true).fetchSemanticsNodes()
        assertTrue("the parser's own message should stay in the log", leaked.isEmpty())
    }

    // --- the weekly automatic backup --------------------------------------

    /** Off is what a device that has never been asked reads, and the row has to say so. */
    @Test
    fun `the auto-backup row is off and says nothing is scheduled`() {
        setContent()

        composeTestRule.onNodeWithText("Weekly auto-backup").performScrollTo().assertIsOff()
        composeTestRule.onNodeWithText("Off. Nothing is written on a schedule.").assertIsDisplayed()
    }

    /** The whole row is the control, so the event carries the value the switch moved *to*. */
    @Test
    fun `tapping the row asks for it to be turned on`() {
        setContent()

        composeTestRule.onNodeWithText("Weekly auto-backup").performScrollTo().performClick()

        assertEquals(listOf(BackupEvent.AutoBackupToggled(true)), events)
    }

    @Test
    fun `tapping a row that is on asks for it to be turned off`() {
        setContent(autoBackup = AutoBackupSchedule(enabled = true))

        composeTestRule.onNodeWithText("Weekly auto-backup").performScrollTo().performClick()

        assertEquals(listOf(BackupEvent.AutoBackupToggled(false)), events)
    }

    /** The report a job the user never sees makes: the day it last wrote a file. */
    @Test
    fun `a schedule that has run names the day it last did`() {
        setContent(autoBackup = AutoBackupSchedule(enabled = true, lastSuccessAt = LAST_BACKUP_AT))

        composeTestRule.onNodeWithText("Weekly auto-backup").performScrollTo().assertIsOn()
        composeTestRule.onNodeWithText("Last backup Apr 26").assertIsDisplayed()
    }

    @Test
    fun `a schedule that has never run says when the first one will be`() {
        setContent(autoBackup = AutoBackupSchedule(enabled = true))

        composeTestRule.onNodeWithText("On. The first one runs", substring = true).performScrollTo().assertIsDisplayed()
    }

    /**
     * Both facts at once: the failure, and the backup that is still there behind it.
     *
     * A design that let one overwrite the other would tell the user something is wrong while
     * hiding how much it has cost, or the reverse.
     */
    @Test
    fun `a run that gave up is reported beside the last good backup`() {
        setContent(
            autoBackup = AutoBackupSchedule(
                enabled = true,
                lastSuccessAt = LAST_BACKUP_AT,
                lastFailureAt = LAST_FAILURE_AT,
            ),
        )

        composeTestRule.onNodeWithText("The last automatic backup did not finish", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Last backup Apr 26").assertIsDisplayed()
    }

    /** A schedule that is off has no next run, so there is nothing to promise about one. */
    @Test
    fun `a failure is not reported once the schedule is off`() {
        setContent(autoBackup = AutoBackupSchedule(enabled = false, lastFailureAt = LAST_FAILURE_AT))

        val reported = composeTestRule
            .onAllNodesWithText("The last automatic backup did not finish", substring = true)
            .fetchSemanticsNodes()
        assertTrue("a schedule that is off should not report on runs it will not make", reported.isEmpty())
    }

    /**
     * The most important sentence in the section, and it is on the screen whichever way the
     * switch is set: somebody who believes an automatic backup survives a reinstall has been
     * actively misled.
     */
    @Test
    fun `the screen says automatic backups go with the app`() {
        setContent()

        composeTestRule.onNodeWithText("deleted if you uninstall the app", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** A switch that moved back on its own needs to say why. */
    @Test
    fun `a toggle that could not be stored explains itself`() {
        setContent(autoBackupSaveFailed = true)

        composeTestRule.onNodeWithText("That switch could not be saved", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun assertSaysNothingChanged() {
        composeTestRule.onNodeWithText("Nothing on this device was changed", substring = true).assertIsDisplayed()
    }

    private fun setContent(
        footprint: DataFootprint = DataFootprint(),
        phase: BackupPhase = BackupPhase.Idle,
        pendingRestoreUri: String? = null,
        lastExport: ExportOutcome? = null,
        lastRestore: RestoreOutcome? = null,
        lastSave: SaveOutcome? = null,
        autoBackup: AutoBackupSchedule = AutoBackupSchedule(),
        autoBackupSaveFailed: Boolean = false,
        appVersion: String = "1.0",
    ) {
        composeTestRule.setContent {
            OpenGymTheme {
                BackupScreen(
                    state = BackupUiState(
                        footprint = footprint,
                        phase = phase,
                        pendingRestoreUri = pendingRestoreUri,
                        lastExport = lastExport,
                        lastRestore = lastRestore,
                        lastSave = lastSave,
                        autoBackup = autoBackup,
                        autoBackupSaveFailed = autoBackupSaveFailed,
                    ),
                    appVersion = appVersion,
                    onEvent = events::add,
                    onBack = {},
                    onPickFile = { pickerOpened++ },
                    onSaveCopy = saved::add,
                    onShare = shared::add,
                )
            }
        }
    }

    private companion object {

        /** April 2024, so the "training since" line has a month a test can name. */
        const val FIRST_SESSION_AT = 1_711_929_600_000L

        const val SOME_URI = "content://downloads/opengym-backup.json"

        /** 2026-04-26, the Sunday the wireframe's "last: APR 26" names. */
        const val LAST_BACKUP_AT = 1_777_161_600_000L

        /** A week after it, so a failure is the newer of the two facts the row carries. */
        const val LAST_FAILURE_AT = 1_777_766_400_000L

        /** A finished export, which is what both card actions are about. */
        val EXPORTED = ExportOutcome.Succeeded(
            fileName = "opengym-backup-2026-09-06-1432.json",
            rowsWritten = 52_180,
        )

        val FOOTPRINT = DataFootprint(
            sessions = 184,
            sets = 2_140,
            templates = 6,
            personalRecords = 23,
            customExercises = 2,
            bodyMeasurements = 61,
            firstSessionAt = FIRST_SESSION_AT,
            databaseBytes = 2_411_724,
        )
    }
}
