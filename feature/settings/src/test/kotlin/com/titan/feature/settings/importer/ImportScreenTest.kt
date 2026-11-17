package com.titan.feature.settings.importer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryImportRequest
import com.titan.core.domain.importer.HistoryImportSource
import com.titan.core.domain.importer.ImportSkips
import com.titan.core.testing.assertAccessible
import com.titan.core.ui.theme.TitanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The import screen, driven through its stateless half with hand-built states: every stage
 * renders what it is for and sends the event its buttons stand for.
 *
 * What is held hardest is what the spec makes a promise of: the progress is a polite live region
 * that speaks in tens of a percent, the copy of the cancel question changes once the history is
 * saved, a refusal's English reason never reaches the screen, and what was left out is shown
 * plainly when there is any and not at all when there is none.
 */
@RunWith(RobolectricTestRunner::class)
class ImportScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<ImportEvent>()
    private var filePickerOpened = 0
    private var formatOpened = 0
    private var libraryOpened = 0
    private var backPressed = 0

    // --- choosing ----------------------------------------------------------------

    @Test
    fun `every app with a parser is offered, and a tap opens its guide`() {
        setContent(ImportStage.ChooseSource)

        listOf("Strong", "Hevy", "FitNotes", "JEFIT", "Fitbod", "StrongLifts", "Plain CSV").forEach { name ->
            composeTestRule.onNodeWithText(name).performScrollTo().assertIsDisplayed()
        }
        composeTestRule.onNodeWithText("JEFIT").performScrollTo().performClick()

        assertEquals(listOf<ImportEvent>(ImportEvent.SourceChosen(HistoryImportSource.JEFIT)), events)
    }

    @Test
    fun `the list says an import adds to the history rather than replacing it`() {
        setContent(ImportStage.ChooseSource)

        composeTestRule.onNodeWithText("Nothing here is replaced or deleted", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a guide's button opens the file picker`() {
        setContent(ImportStage.Guide(HistoryImportSource.STRONG))

        composeTestRule.onNodeWithText("Choose file").performScrollTo().performClick()

        assertEquals(1, filePickerOpened)
    }

    @Test
    fun `a guide reads each step as one sentence with its number`() {
        setContent(ImportStage.Guide(HistoryImportSource.STRONG))

        composeTestRule.onNodeWithContentDescription("Step 1: In Strong, open Settings.").assertIsDisplayed()
    }

    @Test
    fun `the FitNotes guide says its backup file is not the one`() {
        setContent(ImportStage.Guide(HistoryImportSource.FITNOTES))

        composeTestRule.onNodeWithText(".fitnotes backup file cannot be imported", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `only the plain CSV guide links to the format, and the link opens it`() {
        setContent(ImportStage.Guide(HistoryImportSource.PLAIN_CSV))

        composeTestRule.onNodeWithText("Read the CSV format on GitHub").performScrollTo().performClick()

        assertEquals(1, formatOpened)
    }

    @Test
    fun `the back arrow on a guide returns to the list rather than leaving`() {
        setContent(ImportStage.Guide(HistoryImportSource.HEVY))

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf<ImportEvent>(ImportEvent.GuideClosed), events)
        assertEquals(0, backPressed)
    }

    @Test
    fun `the warning about a workout in progress starts or holds the import`() {
        setContent(
            ImportStage.Guide(HistoryImportSource.HEVY),
            pendingRequest = HistoryImportRequest(HistoryImportSource.HEVY, "content://x/1"),
        )

        composeTestRule.onNodeWithText("A workout is in progress").assertIsDisplayed()
        composeTestRule.onNodeWithText("Import now").performClick()
        composeTestRule.onNodeWithText("Not now").performClick()

        assertEquals(listOf(ImportEvent.StartConfirmed, ImportEvent.StartDismissed), events)
    }

    // --- running -----------------------------------------------------------------

    @Test
    fun `writing is a determinate bar spoken in tens of a percent, as a polite live region`() {
        setContent(ImportStage.Running(HistoryImportSource.HEVY, HistoryImportProgress.Writing(1_204, 3_120)))

        composeTestRule.onNodeWithContentDescription("Saving workouts 30%")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun `storing photos says the history is saved and its button stops only the photos`() {
        setContent(ImportStage.Running(HistoryImportSource.HEVY, HistoryImportProgress.StoringPhotos(12, 40)))

        composeTestRule.onNodeWithContentDescription("Storing photos 30%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your history is saved", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Stop storing photos").performScrollTo().performClick()

        assertEquals(listOf<ImportEvent>(ImportEvent.CancelRequested), events)
    }

    @Test
    fun `reading has no figure to repeat, and says the screen can be left`() {
        setContent(ImportStage.Running(HistoryImportSource.STRONG, HistoryImportProgress.Reading))

        composeTestRule.onNodeWithContentDescription("Reading the file…").assertIsDisplayed()
        composeTestRule.onNodeWithText("You can leave this screen", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel import").performScrollTo().performClick()

        assertEquals(listOf<ImportEvent>(ImportEvent.CancelRequested), events)
    }

    @Test
    fun `the cancel question says nothing will be imported while the history is being written`() {
        setContent(
            ImportStage.Running(HistoryImportSource.HEVY, HistoryImportProgress.Writing(3, 10)),
            confirmingCancel = true,
        )

        composeTestRule.onNodeWithText("Cancel the import?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Nothing will be imported", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Keep importing").performClick()

        assertEquals(listOf<ImportEvent>(ImportEvent.CancelDismissed), events)
    }

    @Test
    fun `the cancel question says the history stays once the photos are being stored`() {
        setContent(
            ImportStage.Running(HistoryImportSource.HEVY, HistoryImportProgress.StoringPhotos(3, 10)),
            confirmingCancel = true,
        )

        composeTestRule.onNodeWithText("Stop storing photos?").assertIsDisplayed()
        composeTestRule.onNodeWithText("already saved and stays", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Stop photos").performClick()

        assertEquals(listOf<ImportEvent>(ImportEvent.CancelConfirmed), events)
    }

    // --- the report --------------------------------------------------------------

    @Test
    fun `the report says what landed and what it earned, once`() {
        setContent(ImportStage.Completed(HistoryImportSource.STRONG, REPORT))

        composeTestRule.onNodeWithText("312 workouts").assertIsDisplayed()
        composeTestRule.onNodeWithText("9,840 sets").assertIsDisplayed()
        composeTestRule.onNodeWithText("140 personal records").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("48,250 XP").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("11 achievements unlocked").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a report with nothing left out has no block for it`() {
        setContent(ImportStage.Completed(HistoryImportSource.STRONG, REPORT))

        composeTestRule.onNode(hasText("Left out", ignoreCase = true)).assertDoesNotExist()
    }

    @Test
    fun `a report with skips names every kind left out`() {
        setContent(
            ImportStage.Completed(
                HistoryImportSource.HEVY,
                REPORT.copy(skipped = ImportSkips(duplicateWorkouts = 14, invalidSets = 3, invalidPhotos = 1)),
            ),
        )

        composeTestRule.onNodeWithText("14 workouts already on this phone").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("3 sets that could not be used").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("1 photo that could not be used").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `photos that would not store are explained as a history that is in`() {
        setContent(ImportStage.Completed(HistoryImportSource.HEVY, REPORT.copy(photosImported = 8, photosFailed = 2)))

        composeTestRule.onNodeWithText(
            "2 photos could not be stored",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Your history is in all the same", substring = true).assertIsDisplayed()
    }

    @Test
    fun `new custom exercises can be reviewed in the library`() {
        setContent(ImportStage.Completed(HistoryImportSource.STRONG, REPORT))

        composeTestRule.onNodeWithText(
            "6 new custom exercises you may want to review",
        ).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Open the exercise library").performScrollTo().performClick()

        assertEquals(1, libraryOpened)
    }

    @Test
    fun `with no custom exercises created there is nothing to review`() {
        setContent(ImportStage.Completed(HistoryImportSource.STRONG, REPORT.copy(exercisesCreated = 0)))

        composeTestRule.onNodeWithText("Open the exercise library").assertDoesNotExist()
    }

    @Test
    fun `done dismisses the report`() {
        setContent(ImportStage.Completed(HistoryImportSource.STRONG, REPORT))

        composeTestRule.onNodeWithText("Done").performScrollTo().performClick()

        assertEquals(listOf<ImportEvent>(ImportEvent.OutcomeDismissed), events)
    }

    // --- failures and the rest ----------------------------------------------------

    @Test
    fun `every refusal has its own sentence, and none shows the engine's reason`() {
        val sentences = mapOf(
            HistoryImportError.NotRecognised to "This file is not a Strong export",
            HistoryImportError.Malformed(REASON) to "Export it from Strong again",
            HistoryImportError.TooLarge(33_554_432) to "too large to import",
            HistoryImportError.Unreadable(REASON) to "Pick it again",
            HistoryImportError.NothingToImport to "holds nothing Titan can import",
            HistoryImportError.StorageFailure(REASON) to "storage is full",
        )
        var error by mutableStateOf<HistoryImportError>(HistoryImportError.NotRecognised)
        composeTestRule.setContent {
            TitanTheme {
                ImportScreen(
                    state = ImportUiState(stage = ImportStage.Failed(HistoryImportSource.STRONG, error)),
                    onEvent = events::add,
                    onBack = {},
                    onChooseFile = {},
                    onOpenFormat = {},
                    onOpenExerciseLibrary = {},
                )
            }
        }

        sentences.forEach { (refusal, sentence) ->
            error = refusal
            composeTestRule.onNodeWithText(sentence, substring = true).assertIsDisplayed()
            composeTestRule.onNodeWithText("trying again is safe", substring = true).assertIsDisplayed()
            composeTestRule.onNode(hasText(REASON, substring = true)).assertDoesNotExist()
        }
    }

    @Test
    fun `a refusal offers the guide again or another app`() {
        setContent(ImportStage.Failed(HistoryImportSource.STRONG, HistoryImportError.NotRecognised))

        composeTestRule.onNodeWithText("Try again").performClick()
        composeTestRule.onNodeWithText("Choose another app").performClick()

        assertEquals(listOf(ImportEvent.TryAgain, ImportEvent.ChooseAnotherApp), events)
    }

    @Test
    fun `an interrupted import is offered again`() {
        setContent(ImportStage.Interrupted(HistoryImportRequest(HistoryImportSource.FITBOD, "content://x/1")))

        composeTestRule.onNodeWithText("The import did not finish").assertIsDisplayed()
        composeTestRule.onNodeWithText("while importing from Fitbod", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Run it again").performClick()
        composeTestRule.onNodeWithText("Not now").performClick()

        assertEquals(listOf(ImportEvent.RunAgain, ImportEvent.OutcomeDismissed), events)
    }

    @Test
    fun `a stop says what it cost`() {
        setContent(ImportStage.Stopped(HistoryImportSource.HEVY, ImportStop.NOTHING_IMPORTED))

        composeTestRule.onNodeWithText("nothing was imported", substring = true).assertIsDisplayed()
    }

    /** A state no preview reaches: the report scrolled to its last button. */
    @Test
    fun `a long report scrolled to its end is accessible`() {
        setContent(
            ImportStage.Completed(HistoryImportSource.HEVY, REPORT.copy(skipped = ImportSkips(duplicateWorkouts = 2))),
        )

        composeTestRule.onNodeWithText("Done").performScrollTo()

        composeTestRule.onRoot().assertAccessible()
    }

    private fun setContent(
        stage: ImportStage,
        pendingRequest: HistoryImportRequest? = null,
        confirmingCancel: Boolean = false,
    ) {
        composeTestRule.setContent {
            TitanTheme {
                ImportScreen(
                    state = ImportUiState(
                        stage = stage,
                        pendingRequest = pendingRequest,
                        confirmingCancel = confirmingCancel,
                    ),
                    onEvent = events::add,
                    onBack = { backPressed++ },
                    onChooseFile = { filePickerOpened++ },
                    onOpenFormat = { formatOpened++ },
                    onOpenExerciseLibrary = { libraryOpened++ },
                )
            }
        }
    }

    private companion object {
        /** An engine reason, which must never reach the screen. */
        const val REASON = "row 4: unexpected token"

        val REPORT = HistoryImportReport(
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
    }
}
