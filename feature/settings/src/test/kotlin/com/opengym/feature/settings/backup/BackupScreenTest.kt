package com.opengym.feature.settings.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
        setContent(
            lastExport = ExportOutcome.Succeeded(
                fileName = "opengym-backup-2026-09-06-1432.json",
                rowsWritten = 52_180,
            ),
        )

        composeTestRule
            .onNodeWithText("opengym-backup-2026-09-06-1432.json", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
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

    private fun assertSaysNothingChanged() {
        composeTestRule.onNodeWithText("Nothing on this device was changed", substring = true).assertIsDisplayed()
    }

    private fun setContent(
        footprint: DataFootprint = DataFootprint(),
        phase: BackupPhase = BackupPhase.Idle,
        pendingRestoreUri: String? = null,
        lastExport: ExportOutcome? = null,
        lastRestore: RestoreOutcome? = null,
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
                    ),
                    appVersion = appVersion,
                    onEvent = events::add,
                    onBack = {},
                    onPickFile = { pickerOpened++ },
                )
            }
        }
    }

    private companion object {

        /** April 2024, so the "training since" line has a month a test can name. */
        const val FIRST_SESSION_AT = 1_711_929_600_000L

        const val SOME_URI = "content://downloads/opengym-backup.json"

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
