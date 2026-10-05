package com.titan.app.store

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.titan.app.HiltTestActivity
import com.titan.app.navigation.TitanAppState
import com.titan.app.navigation.TopLevelDestination
import com.titan.app.navigation.rememberTitanAppState
import com.titan.app.ui.TitanApp
import com.titan.core.data.di.ClockModule
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.backup.model.ImportProgress
import com.titan.core.domain.model.toSessionPlan
import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.repository.TemplateRepository
import com.titan.core.domain.time.Clock
import com.titan.core.domain.usecase.ApplyTemplateUseCase
import com.titan.core.domain.usecase.ImportUseCase
import com.titan.core.domain.usecase.NextLoadUseCase
import com.titan.core.domain.usecase.StartPlannedSessionUseCase
import com.titan.core.domain.usecase.StartSessionUseCase
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.navigation.ExerciseProgressionDestination
import com.titan.feature.session.navigation.ActiveSessionDestination
import com.titan.feature.session.navigation.NewSessionDestination
import com.titan.feature.settings.navigation.BackupDestination
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import java.util.TimeZone
import javax.imageio.ImageIO
import javax.inject.Inject
import com.titan.core.data.R as DataR
import com.titan.feature.session.R as SessionR

/**
 * The Play listing's phone screenshots (#46), captured from the real app over the demo dataset.
 *
 * Run by `make store-screenshots`; skipped otherwise, because the output is a store asset rather
 * than a reference anything is compared against. See `tools/demo/README.md`.
 *
 * ### What a capture is
 *
 * The whole app — the real graph, the real database, every screen's own ViewModel — after the
 * locale's demo export has been restored through the same import a user runs from Backup &
 * restore. Nothing is faked but the clock, which is pinned to [StoreAssets.DEMO_NOW] through the
 * graph's [ClockModule] binding, so "today" on every dated screen is the day the demo was
 * written for however long after it the screenshots are retaken.
 *
 * ### The size
 *
 * 1080 × 1920: a 360 × 640 dp window at xxhdpi. #46 asked for 1080 × 2400, which Play refuses —
 * a screenshot's long side may be at most twice its short side, and 2400 is 2.2 times 1080.
 * 9:16 is also the shape Play needs for a listing to be eligible for its promotional surfaces.
 *
 * No status bar, no navigation bar and no device frame: the capture is the app's own window, and
 * the store's guidelines discourage frames and overlaid text alike.
 */
@HiltAndroidTest
@UninstallModules(ClockModule::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class)
class StoreScreenshotTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<HiltTestActivity>()

    @BindValue
    @JvmField
    val clock: Clock = Clock { StoreAssets.DEMO_NOW }

    @Inject lateinit var restore: ImportUseCase

    @Inject lateinit var exercises: ExerciseRepository

    @Inject lateinit var templates: TemplateRepository

    @Inject lateinit var sessions: SessionRepository

    @Inject lateinit var sessionState: SessionStateRepository

    @Inject lateinit var applyTemplate: ApplyTemplateUseCase

    @Inject lateinit var nextLoad: NextLoadUseCase

    private lateinit var appState: TitanAppState

    private val defaultZone: TimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        assumeTrue("store screenshots are written by `make store-screenshots`", StoreAssets.isRecording)
        // The demo's timestamps are UTC evenings; read in UTC, every screen shows the hour the
        // generator meant, wherever the machine running this happens to be.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        hilt.inject()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultZone)
    }

    @Test
    @Config(qualifiers = "en-rUS-$WINDOW")
    fun `English listing`() = capture(StoreLocale.EN_US)

    @Test
    @Config(qualifiers = "fr-rFR-$WINDOW")
    fun `French listing`() = capture(StoreLocale.FR_FR)

    @Test
    @Config(qualifiers = "es-rES-$WINDOW")
    fun `Spanish listing`() = capture(StoreLocale.ES_ES)

    @Test
    @Config(qualifiers = "pt-rBR-$WINDOW")
    fun `Portuguese listing`() = capture(StoreLocale.PT_BR)

    /**
     * Every screen #46 lists, numbered in the order the listing shows them. Captured in a
     * different order — the workout last — because starting one puts a resume banner on Home.
     */
    private fun capture(locale: StoreLocale) {
        val restored = runBlocking { restore(ImportSource { StoreAssets.demoFile(locale).inputStream() }).last() }
        check(restored is ImportProgress.Complete) { "${StoreAssets.demoFile(locale)} did not restore: $restored" }

        val directory = StoreAssets.screenshotDir(locale)
        directory.listFiles { file -> file.extension == "png" }?.forEach(File::delete)
        directory.mkdirs()

        composeTestRule.setContent {
            // The same two switches `EndToEndJourneyTest` turns off, for the same reasons: an
            // animation that never ends — the recap's ceremony, a caret — keeps Robolectric from
            // ever reporting the screen idle. Reduced motion is also what lands every count-up
            // on its final figure, which is the figure a screenshot should show.
            val window = LocalWindowInfo.current
            val unfocused = remember(window) {
                object : WindowInfo by window {
                    override val isWindowFocused: Boolean get() = false
                }
            }
            CompositionLocalProvider(LocalWindowInfo provides unfocused) {
                TitanTheme(reducedMotion = true) {
                    appState = rememberTitanAppState()
                    TitanApp(startsOnOnboarding = false, appState = appState)
                }
            }
        }

        shoot(directory, "1_home")

        navigate { appState.navigateToTopLevelDestination(TopLevelDestination.STATS) }
        shoot(directory, "3_stats")

        val squat = runBlocking { exerciseNamed(SQUAT) }
        navigate { appState.navController.navigate(ExerciseProgressionDestination(squat)) }
        shoot(directory, "4_exercise_progression", waitFor = SQUAT)

        navigate { appState.navController.navigate(NewSessionDestination()) }
        shoot(directory, "5_templates")

        navigate { appState.navController.navigate(BackupDestination) }
        shoot(directory, "6_backup")

        val sessionId = runBlocking { startTheNextWorkout() }
        navigate { appState.navController.navigate(ActiveSessionDestination(sessionId)) }
        waitForText(DEADLIFT)
        // The warm-up card leads a fresh workout and pushes the sets — and the next-target line
        // under them, which is what this screenshot is for — below the fold. A lifter a quarter
        // of an hour in has long since answered it.
        composeTestRule.onNodeWithText(composeTestRule.activity.getString(SessionR.string.active_mobility_dismiss))
            .performClick()
        shoot(directory, "2_active_session")
    }

    private fun navigate(action: () -> Unit) {
        composeTestRule.runOnUiThread(action)
    }

    /**
     * Waits for the screen to stop changing, then writes it as an opaque PNG.
     *
     * "Idle" is not enough on its own: every screen reads Room on a background dispatcher, which
     * Compose's idling does not track, so a screen can be idle and still empty. The semantics
     * tree is sampled until it reads the same several times running — the screen has drawn what
     * its data says — and only then captured.
     *
     * @param waitFor text the screen draws only once its data has arrived, for a screen whose
     *   loading state holds still long enough to pass for settled. Exercise names are the
     *   catalogue's, in English in every locale, which is what makes them a handle here.
     */
    private fun shoot(
        directory: File,
        name: String,
        waitFor: String? = null,
    ) {
        waitFor?.let(::waitForText)
        var previous = ""
        var stable = 0
        var samples = 0
        while (stable < STABLE_SAMPLES) {
            check(samples++ < MAX_SAMPLES) { "$name never settled" }
            composeTestRule.waitForIdle()
            Thread.sleep(SAMPLE_INTERVAL_MILLIS)
            val tree = composeTestRule.onRoot(useUnmergedTree = true).printToString()
            stable = if (tree == previous) stable + 1 else 0
            previous = tree
        }

        val raw = File(directory, "$name.raw.png")
        composeTestRule.onRoot().captureRoboImage(
            file = raw,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        // Play refuses a screenshot with an alpha channel, even a fully opaque one.
        val captured = ImageIO.read(raw)
        val opaque = BufferedImage(captured.width, captured.height, BufferedImage.TYPE_INT_RGB)
        opaque.createGraphics().apply {
            drawImage(captured, 0, 0, null)
            dispose()
        }
        ImageIO.write(opaque, "png", File(directory, "$name.png"))
        raw.delete()
    }

    private fun waitForText(text: String) {
        composeTestRule.waitUntil(LOAD_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private suspend fun exerciseNamed(name: String): Long =
        exercises.searchByPartialName(name).first()
            .first { it.name == name && !it.isCustom && it.machineBrand == null }
            .id

    /**
     * Starts the workout that comes next in the demo's rotation — pull, on the Wednesday after
     * the last push — a quarter of an hour in, with the deadlifts done at the load the app
     * suggested for them. That is the state the screen is most itself in: work ticked off above,
     * and the next-target line (#216) on the set the screen offers next.
     *
     * Started through the real use cases, but with their clock pinned to the demo's: the
     * lifecycle use cases stamp rows with the wall clock rather than the graph's, and a workout
     * that started "in the future" would draw a stopped clock.
     */
    private suspend fun startTheNextWorkout(): Long {
        val context = composeTestRule.activity
        val pull = templates.getAll().first().single {
            it.name == context.getString(
                DataR.string.default_template_pull,
            )
        }
        val startedAt = StoreAssets.DEMO_NOW - WORKOUT_ELAPSED_MILLIS
        val start = StartPlannedSessionUseCase(
            startSession = StartSessionUseCase(sessions, sessionState, clock = { startedAt }),
            sessionRepository = sessions,
            sessionStateRepository = sessionState,
        )
        val sessionId = start(applyTemplate(pull.id).getOrThrow().toSessionPlan()).getOrThrow()

        val first = sessions.getExercises(sessionId).first().minBy { it.position }
        val target = checkNotNull(nextLoad(first.exerciseId, sessionId).first()) { "the demo has no history for it" }
        var at = startedAt + WARM_UP_MILLIS
        sessions.updateSets(
            sessions.getSets(first.id).first().sortedBy { it.setNumber }.map { set ->
                at += SET_SPACING_MILLIS
                set.copy(
                    reps = target.reps,
                    weight = target.weight,
                    isCompleted = true,
                    completedAt = at,
                    updatedAt = at,
                )
            },
        )
        return sessionId
    }

    private companion object {

        /** 1080 × 1920 px. See the class doc for why not 1080 × 2400. */
        const val WINDOW = "w360dp-h640dp-xxhdpi"

        // The progression screenshot's lift: the squat rather than the bench, whose catalogue
        // name — "Barbell Bench Press - Medium Grip" — is too long for the title bar.
        const val SQUAT = "Barbell Squat"
        const val DEADLIFT = "Barbell Deadlift"

        const val WORKOUT_ELAPSED_MILLIS = 16 * 60 * 1_000L
        const val WARM_UP_MILLIS = 6 * 60 * 1_000L
        const val SET_SPACING_MILLIS = 3 * 60 * 1_000L

        const val LOAD_TIMEOUT_MILLIS = 30_000L
        const val STABLE_SAMPLES = 4
        const val MAX_SAMPLES = 200
        const val SAMPLE_INTERVAL_MILLIS = 150L
    }
}
