package com.opengym.feature.history.plate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.plate.PlateSolution
import com.opengym.core.domain.plate.PlateStock
import com.opengym.core.ui.component.BarbellDiagram
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.PlateLegend
import com.opengym.core.ui.format.formatWeight
import com.opengym.core.ui.format.formatWeightValue
import com.opengym.core.ui.format.plateIncrement
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.format.weightToMilligrams
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.plate.component.EquipmentSheet
import com.opengym.feature.history.plate.component.TargetWeightDialog
import kotlinx.coroutines.flow.filterNotNull

/**
 * The plate calculator, wired to its ViewModel.
 *
 * @param onBack leave the screen.
 */
@Composable
fun PlateCalculatorRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlateCalculatorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    PlateCalculatorScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * What to hang on the bar to reach a weight.
 *
 * ### One number, and everything else is an answer to it
 *
 * The target is the largest thing on the screen because it is the only thing the user changes.
 * Below it, in order: how far off it the rack can get, the bar as it will look loaded, and the
 * plates to carry over. Nothing here is a control except the target's own steppers and the way
 * into the equipment editor.
 *
 * ### It never rounds the target to something loadable
 *
 * When the rack cannot make the weight, the big figure stays at what was asked for and a line
 * under it says what will actually be on the bar. Snapping the target to the nearest loadable
 * weight would be the more comfortable design and would be a lie — see `PlateCalculator`. The
 * same line covers the other honest failure, a target under the bar.
 *
 * ### The unit system is read here and nowhere deeper
 *
 * Every stepper, every preset and the typed field convert in this file, so the ViewModel and the
 * domain see milligrams only — which is what lets `+2.5 kg` and `+5 lb` be the same event.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlateCalculatorScreen(
    state: PlateCalculatorUiState,
    onEvent: (PlateCalculatorEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.let { stringResource(it.textRes()) }

    // Marked shown before the snackbar is, and keyed on `Unit` — the settings screen documents
    // both halves: clearing afterwards leaves the message for a recomposed screen to show twice,
    // and re-keying on it cancels the `showSnackbar` it is in the middle of.
    val currentMessage by rememberUpdatedState(message)
    LaunchedEffect(Unit) {
        snapshotFlow { currentMessage }
            .filterNotNull()
            .collect { text ->
                onEvent(PlateCalculatorEvent.MessageShown)
                snackbarHostState.showSnackbar(text)
            }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { PlateCalculatorTopBar(onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(OpenGymTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.section),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TargetWeight(
                target = state.target,
                onEdit = { onEvent(PlateCalculatorEvent.SheetRequested(PlateCalculatorSheet.TARGET)) },
            )

            TargetSteppers(onStep = { onEvent(PlateCalculatorEvent.TargetStepped(it)) })

            SolutionNotice(solution = state.solution)

            Eyebrow(
                text = stringResource(
                    R.string.plate_per_side_bar,
                    formatWeight(state.preferences.barWeight),
                ),
            )

            BarbellDiagram(perSide = state.perSide, denominations = state.denominations)

            PlateLegend(perSide = state.perSide, denominations = state.denominations)

            EquipmentButton(
                onClick = {
                    onEvent(PlateCalculatorEvent.SheetRequested(PlateCalculatorSheet.EQUIPMENT))
                },
            )
        }
    }

    when (state.sheet) {
        PlateCalculatorSheet.TARGET -> TargetWeightDialog(
            target = state.target,
            onConfirm = { onEvent(PlateCalculatorEvent.TargetEntered(it)) },
            onDismiss = { onEvent(PlateCalculatorEvent.SheetDismissed) },
        )

        PlateCalculatorSheet.EQUIPMENT -> EquipmentSheet(
            barWeight = state.preferences.barWeight,
            inventory = state.preferences.plates,
            onBarWeightChange = { onEvent(PlateCalculatorEvent.BarWeightSelected(it)) },
            onPairsChange = { weight, pairs ->
                onEvent(PlateCalculatorEvent.PlatePairsSelected(weight, pairs))
            },
            onReset = { onEvent(PlateCalculatorEvent.InventoryReset) },
            onDismiss = { onEvent(PlateCalculatorEvent.SheetDismissed) },
        )

        null -> Unit
    }
}

/**
 * What is loaded, or an empty list — the shape both `:core:ui` plate components take.
 *
 * A target under the bar has no loading at all, which is not the same as a bar with nothing on
 * it; [SolutionNotice] is what tells those two apart on screen.
 */
private val PlateCalculatorUiState.perSide
    get() = (solution as? PlateSolution.Loaded)?.perSide.orEmpty()

/**
 * The number the screen is about, and the way to type one.
 *
 * Tapping it opens the field rather than turning it into one: a 56sp text field with a keyboard
 * over it is a different screen, and this one is meant to be read at arm's length with a bar in
 * your hands. The suffix is drawn separately and small, the way every big figure in the app is.
 */
@Composable
private fun TargetWeight(target: Long, onEdit: () -> Unit) {
    val description = stringResource(R.string.plate_target_edit, formatWeight(target))

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Eyebrow(text = stringResource(R.string.plate_target))
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.padding(top = OpenGymTheme.spacing.md),
        ) {
            Text(
                text = formatWeightValue(target),
                style = OpenGymTheme.typography.numericDisplay,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = LocalUnitSystem.current.weightSuffix,
                style = OpenGymTheme.typography.numeric,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(
                    start = OpenGymTheme.spacing.xs,
                    bottom = OpenGymTheme.spacing.md,
                ),
            )
        }
    }
}

/**
 * The four steps: a pair of change plates either way, and a pair of tens.
 *
 * Sized in the unit the plates are stamped in rather than converted from each other, which is
 * why a metric user gets ±2.5 / ±10 and an imperial one ±5 / ±20 — see `plateIncrement`. Both
 * are *pair* steps, so every press moves the bar by something that can actually be loaded.
 */
@Composable
private fun TargetSteppers(onStep: (Long) -> Unit) {
    val unitSystem = LocalUnitSystem.current
    val small = unitSystem.plateIncrement
    val large = small * LARGE_STEP_MULTIPLE

    Row(
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        listOf(-large, -small, small, large).forEach { step ->
            val milligrams = weightToMilligrams(step, unitSystem)
            OutlinedButton(
                onClick = { onStep(milligrams) },
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(
                    horizontal = OpenGymTheme.spacing.md,
                    vertical = OpenGymTheme.spacing.lg,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    // The minus is a real minus sign, not a hyphen: it is a number's sign, and
                    // it sits beside a `+` that is full width.
                    text = if (step < 0) {
                        "−${formatWeightValue(weightToMilligrams(-step, unitSystem))}"
                    } else {
                        "+${formatWeightValue(milligrams)}"
                    },
                    style = OpenGymTheme.typography.numeric,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The line that keeps the big number honest.
 *
 * Drawn in the accent, directly under the target, because it contradicts it — anywhere else on
 * the screen and it is a caption somebody scrolls past on the way to the plates. Nothing is
 * drawn at all when the target is reachable, so its presence is the signal.
 */
@Composable
private fun SolutionNotice(solution: PlateSolution) {
    val text = when {
        solution is PlateSolution.BelowBar ->
            stringResource(R.string.plate_below_bar, formatWeight(solution.barWeight))

        solution is PlateSolution.Loaded && !solution.isExact -> stringResource(
            R.string.plate_shortfall,
            formatWeight(solution.achieved),
            formatWeight(solution.shortfall),
        )

        else -> return
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The way into the one thing on this screen that is stored: the bar and the rack. */
@Composable
private fun EquipmentButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.plate_equipment),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** The screen's own bar. It sits outside the tab scaffold, so the shell has drawn nothing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlateCalculatorTopBar(onBack: () -> Unit) {
    Column {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.plate_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.plate_back),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        // Ruled off rather than floated on elevation, like every other bar in the app.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun PlateCalculatorMessage.textRes(): Int = when (this) {
    PlateCalculatorMessage.SAVE_FAILED -> R.string.plate_save_failed
}

/** The big step is four of the small one: 10 kg on a metric rack, 20 lb on an imperial one. */
private const val LARGE_STEP_MULTIPLE = 4

// ─── Previews ───────────────────────────────────────────────────────────────────────────

/** Tall enough to capture the whole screen; a preview clips at the device height otherwise. */
private const val FULL_SCREEN_HEIGHT_DP = 1000

/** A hundred kilos: two plates and two change plates a side on the standard metric rack. */
private const val PREVIEW_HUNDRED_KILOS = 100_000_000L

/** A kilo above what the rack's 1.25 kg grid can make. */
private const val PREVIEW_OFF_GRID = 101_000_000L

/** Under the bar. */
private const val PREVIEW_UNDER_THE_BAR = 15_000_000L

/** 45 lb and 225 lb, as milligrams — see `PlateInventory` for why these are not round. */
private const val PREVIEW_POUND_BAR = 20_411_657L
private const val PREVIEW_TWO_TWENTY_FIVE = 102_058_283L

/** A garage rack: a pair of 20s and a pair of 5s, asked for 120 kg. */
private const val PREVIEW_HOME_TARGET = 120_000_000L
private const val PREVIEW_HOME_BIG_PLATE = 20_000_000L
private const val PREVIEW_HOME_SMALL_PLATE = 5_000_000L

private fun previewState(target: Long, preferences: UserPreferences = UserPreferences()) =
    PlateCalculatorUiState(target = target, preferences = preferences)

@Preview(name = "Plate calculator", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun PlateCalculatorScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        PlateCalculatorScreen(state = previewState(PREVIEW_HUNDRED_KILOS), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Plate calculator - dark", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun PlateCalculatorScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        PlateCalculatorScreen(state = previewState(PREVIEW_HUNDRED_KILOS), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Plate calculator - not on the rack", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun PlateCalculatorScreenShortPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        // 101 kg is half a kilo a side off the grid a metric rack steps in.
        PlateCalculatorScreen(state = previewState(PREVIEW_OFF_GRID), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Plate calculator - under the bar", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun PlateCalculatorScreenBelowBarPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        PlateCalculatorScreen(state = previewState(PREVIEW_UNDER_THE_BAR), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Plate calculator - imperial", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun PlateCalculatorScreenImperialPreview() {
    val imperial = UserPreferences(
        unitSystem = UnitSystem.IMPERIAL,
        barWeight = PREVIEW_POUND_BAR,
    )
    OpenGymTheme(theme = ThemePreference.LIGHT, unitSystem = UnitSystem.IMPERIAL) {
        // 225 lb, and the steppers relabel themselves to ±5 and ±20.
        PlateCalculatorScreen(state = previewState(PREVIEW_TWO_TWENTY_FIVE, imperial), onEvent = {}, onBack = {})
    }
}

@Preview(name = "Plate calculator - a thin rack", showBackground = true, heightDp = FULL_SCREEN_HEIGHT_DP)
@Composable
fun PlateCalculatorScreenThinRackPreview() {
    val homeGym = UserPreferences(
        plateInventory = PlateInventory.of(
            listOf(
                PlateStock(weight = PREVIEW_HOME_BIG_PLATE, pairs = 2),
                PlateStock(weight = PREVIEW_HOME_SMALL_PLATE, pairs = 2),
            ),
        ),
    )
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        PlateCalculatorScreen(state = previewState(PREVIEW_HOME_TARGET, homeGym), onEvent = {}, onBack = {})
    }
}
