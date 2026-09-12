package com.opengym.feature.progression.body.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.BodyMeasurement
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.validation.BodyMeasurementValidator
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.SheetDragHandle
import com.opengym.core.ui.format.formatLengthValue
import com.opengym.core.ui.format.formatPercentTenthsValue
import com.opengym.core.ui.format.formatSessionDate
import com.opengym.core.ui.format.formatWeightValue
import com.opengym.core.ui.format.lengthSuffix
import com.opengym.core.ui.format.parseLengthMillimetres
import com.opengym.core.ui.format.parsePercentTenths
import com.opengym.core.ui.format.parseWeightMilligrams
import com.opengym.core.ui.format.percentSuffix
import com.opengym.core.ui.format.weightSuffix
import com.opengym.core.ui.theme.LocalUnitSystem
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.body.NEW_ENTRY

/**
 * The log form, in a bottom sheet.
 *
 * A sheet rather than a screen, for the reason `ExerciseFormSheet` is one: measuring yourself
 * is something you do *from* the body screen and go straight back to, and pushing a
 * destination would take away the figures you are about to change.
 *
 * The sheet is the wrapper only; [MeasurementForm] is the part with the layout in it, so a
 * test can drive the form without a sheet's animation and window behaviour in the way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementFormSheet(
    entry: BodyMeasurement,
    onSave: (BodyMeasurement) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isSaving: Boolean = false,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        // Seven fields is taller than half a phone and every one of them matters, so there is
        // no half-expanded state worth stopping at on the way.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { SheetDragHandle() },
    ) {
        MeasurementForm(entry = entry, onSave = onSave, onCancel = onDismiss, isSaving = isSaving)
    }
}

/**
 * Weight, four circumferences, body fat and a note — every one of them optional.
 *
 * ### It holds its own text, and hands back a finished entry
 *
 * The fields are `rememberSaveable` here rather than hoisted into the ViewModel, the split
 * `BarWeightDialog` documents: a half-typed number is not something any other part of the app
 * has an opinion about, and hoisting it would put a keystroke through the ViewModel and back on
 * every character. What leaves is one [BodyMeasurement], assembled on save.
 *
 * This is also the only place that *can* assemble it. Every figure is typed in the user's own
 * units and stored in canonical ones, and the conversion reads [LocalUnitSystem], which is
 * ambient in the composition and unavailable to a ViewModel. The seed and the parse go through
 * the same pair of formatters in both directions, so a value saved untouched still *reads* as
 * the figure the box was showing — see `LengthFormatTest` and `UnitRoundTripTest`, which assert
 * that round trip over the whole grid of displayable figures. What it does not promise is the
 * stored integer: a tape unit that is not a decimal fraction of a millimetre cannot survive
 * `mm → in → mm` exactly, so an imperial user saving a seeded 1,020 mm chest stores 1,021 —
 * one millimetre, under half the tenth of an inch the box is read to, and it does not move
 * again.
 *
 * The state is keyed on the entry *and* the unit system, so switching to pounds while the sheet
 * is open reseeds the boxes in pounds rather than reading the kilogram figures as pounds.
 *
 * ### The bounds are the validator's, applied as they are typed
 *
 * Each box turns red when what is in it is not a number or falls outside
 * [BodyMeasurementValidator]'s range for that figure, and the save button goes dead while any
 * box is red or while every measurement box is empty — the two things `BodyMeasurementValidator`
 * refuses. Reading the bounds off the validator rather than restating them is what keeps the
 * form and the write path from drifting apart.
 *
 * It also goes dead while [isSaving], which is not a third bound but the same button answering
 * a different question: the sheet is closed on success only, so it is still on screen while the
 * store is being written, and a button that stays live across that window logs the measurement
 * twice for anybody who taps it twice.
 *
 * ### The date is shown and not edited
 *
 * An entry is stamped when it is logged, and the history is ordered by that stamp. Back-dating
 * a weigh-in is a real thing to want and it is not this issue's — the line is drawn so the user
 * can see which entry they are correcting, which is the question a form opened from a history
 * row raises.
 */
@Composable
fun MeasurementForm(
    entry: BodyMeasurement,
    onSave: (BodyMeasurement) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    isSaving: Boolean = false,
) {
    val unitSystem = LocalUnitSystem.current

    var weightText by rememberSaveable(entry, unitSystem) {
        mutableStateOf(entry.weight?.let { formatWeightValue(it, unitSystem) }.orEmpty())
    }
    var chestText by rememberSaveable(entry, unitSystem) { mutableStateOf(entry.chest.seed(unitSystem)) }
    var waistText by rememberSaveable(entry, unitSystem) { mutableStateOf(entry.waist.seed(unitSystem)) }
    var armText by rememberSaveable(entry, unitSystem) { mutableStateOf(entry.arm.seed(unitSystem)) }
    var thighText by rememberSaveable(entry, unitSystem) { mutableStateOf(entry.thigh.seed(unitSystem)) }
    var bodyFatText by rememberSaveable(entry, unitSystem) {
        mutableStateOf(entry.bodyFat?.let(::formatPercentTenthsValue).orEmpty())
    }
    var notesText by rememberSaveable(entry) { mutableStateOf(entry.notes.orEmpty()) }

    val weight = weightText.parsed(WEIGHT_RANGE) { parseWeightMilligrams(it, unitSystem) }
    val chest = chestText.parsedLength(unitSystem)
    val waist = waistText.parsedLength(unitSystem)
    val arm = armText.parsedLength(unitSystem)
    val thigh = thighText.parsedLength(unitSystem)
    val bodyFat = bodyFatText.parsed(BODY_FAT_RANGE, ::parsePercentTenths)

    val figures = listOf(weight, chest, waist, arm, thigh, bodyFat)
    // An entry has to record something and every figure has to be within its bound — the two
    // rules `BodyMeasurementValidator` would refuse it for, checked here so the button is dead
    // rather than the save silent. And not while a write is already in flight: the sheet is
    // closed on success only, so the button outlives the tap that pressed it and a second one
    // would log the measurement twice.
    val canSave = figures.none { it.isError } && figures.any { it.value != null } && !isSaving

    val lengthUnit = unitSystem.lengthSuffix

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OpenGymTheme.spacing.screen)
            .padding(bottom = OpenGymTheme.spacing.section),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        Text(
            text = if (entry.id == NEW_ENTRY) {
                stringResource(R.string.body_form_title_new)
            } else {
                stringResource(R.string.body_form_title_edit)
            },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Eyebrow(text = formatSessionDate(entry.createdAt))

        MeasurementField(
            value = weightText,
            onValueChange = { weightText = it },
            label = stringResource(R.string.body_field_weight, unitSystem.weightSuffix),
            isError = weight.isError,
        )

        // Two across, because the four circumferences are one question asked four times and a
        // column of full-width boxes would put the notes field below the fold on every phone.
        // They are also kept together and in the order the card above lists them, so the form
        // and the readout are read the same way round.
        MeasurementFieldRow(
            first = {
                MeasurementField(
                    value = chestText,
                    onValueChange = { chestText = it },
                    label = stringResource(R.string.body_field_chest, lengthUnit),
                    isError = chest.isError,
                    modifier = it,
                )
            },
            second = {
                MeasurementField(
                    value = waistText,
                    onValueChange = { waistText = it },
                    label = stringResource(R.string.body_field_waist, lengthUnit),
                    isError = waist.isError,
                    modifier = it,
                )
            },
        )
        MeasurementFieldRow(
            first = {
                MeasurementField(
                    value = armText,
                    onValueChange = { armText = it },
                    label = stringResource(R.string.body_field_arm, lengthUnit),
                    isError = arm.isError,
                    modifier = it,
                )
            },
            second = {
                MeasurementField(
                    value = thighText,
                    onValueChange = { thighText = it },
                    label = stringResource(R.string.body_field_thigh, lengthUnit),
                    isError = thigh.isError,
                    modifier = it,
                )
            },
        )

        // Full width, beside the weight rather than the tape: it is the other figure the two
        // composition tiles are built from, and it is measured with neither a scale nor a tape.
        MeasurementField(
            value = bodyFatText,
            onValueChange = { bodyFatText = it },
            label = stringResource(R.string.body_field_body_fat, percentSuffix),
            isError = bodyFat.isError,
        )

        OutlinedTextField(
            value = notesText,
            onValueChange = { notesText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = stringResource(R.string.body_field_notes)) },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.body_form_cancel))
            }
            Button(
                onClick = {
                    onSave(
                        entry.copy(
                            weight = weight.value,
                            chest = chest.value,
                            waist = waist.value,
                            arm = arm.value,
                            thigh = thigh.value,
                            bodyFat = bodyFat.value,
                            // Blank clears the note, which is the only way to take one back
                            // off an entry — see `BodyMeasurementRepository.update`, which
                            // writes every field.
                            notes = notesText.trim().takeIf(String::isNotBlank),
                        ),
                    )
                },
                enabled = canSave,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.body_form_save))
            }
        }
    }
}

/**
 * One numeric box.
 *
 * [OutlinedTextField] rather than the app's own `OpenGymValueField`, matching every other form
 * in the app: the cell is for a table of figures being logged mid-set, where a floating label
 * and a 56dp height would not fit four across. Here there are seven boxes stacked in a sheet,
 * each of which needs to say what it is *and* what unit it is in, which is the label a Material
 * field already carries.
 *
 * No supporting text under a refused figure. The bounds exist to catch a stray digit — a weight
 * with an extra zero, a waist typed in millimetres — and "must be within 1 mg and 1000 kg" is a
 * sentence about the storage unit rather than advice; the red box beside a number the user can
 * see is the whole message.
 */
@Composable
private fun MeasurementField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        isError = isError,
        label = { Text(text = label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/** Two boxes side by side, each given half the row. */
@Composable
private fun MeasurementFieldRow(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        first(Modifier.weight(1f))
        second(Modifier.weight(1f))
    }
}

/**
 * What is in a box, as the entry would store it: the canonical figure, or the fact that it is
 * not one.
 *
 * Three states rather than a nullable, because an empty box and a box holding `12o` both parse
 * to nothing and are not the same thing — the first is a measurement the user chose not to
 * take, and the second is a typo the field has to go red for.
 */
private data class ParsedFigure<T>(
    val value: T?,
    val isError: Boolean,
)

/**
 * [this] read through [parse] and held to [range].
 *
 * Blank is [ParsedFigure.value] null and **not** an error: every measurement is independently
 * optional, and an empty box is how a user says they did not take that one.
 */
private fun <T : Comparable<T>> String.parsed(
    range: ClosedRange<T>,
    parse: (String) -> T?,
): ParsedFigure<T> {
    if (isBlank()) return ParsedFigure(value = null, isError = false)

    val parsed = parse(this)
    return if (parsed == null || parsed !in range) {
        ParsedFigure(value = null, isError = true)
    } else {
        ParsedFigure(value = parsed, isError = false)
    }
}

/** A circumference box: the same reading, with the tape unit and the shared bound filled in. */
private fun String.parsedLength(unitSystem: UnitSystem): ParsedFigure<Int> =
    parsed(CIRCUMFERENCE_RANGE) { parseLengthMillimetres(it, unitSystem) }

/** A stored circumference as the box shows it, or an empty box where there is none. */
private fun Int?.seed(unitSystem: UnitSystem): String = this?.let { formatLengthValue(it, unitSystem) }.orEmpty()

/*
 * The bounds, read off the validator rather than restated. A form that carried its own numbers
 * is a form that eventually disagrees with the thing that refuses the write.
 */
private val WEIGHT_RANGE = BodyMeasurementValidator.WEIGHT_MIN_MG..BodyMeasurementValidator.WEIGHT_MAX_MG
private val CIRCUMFERENCE_RANGE =
    BodyMeasurementValidator.CIRCUMFERENCE_MIN_MM..BodyMeasurementValidator.CIRCUMFERENCE_MAX_MM
private val BODY_FAT_RANGE = BodyMeasurementValidator.BODY_FAT_MIN..BodyMeasurementValidator.BODY_FAT_MAX

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Measurement form", showBackground = true, widthDp = 360, heightDp = 700)
@Composable
fun MeasurementFormPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        MeasurementForm(entry = previewFormEntry(), onSave = {}, onCancel = {})
    }
}

@Preview(name = "Measurement form - dark", showBackground = true, widthDp = 360, heightDp = 700)
@Composable
fun MeasurementFormDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        MeasurementForm(entry = previewFormEntry(), onSave = {}, onCancel = {})
    }
}

private fun previewFormEntry(): BodyMeasurement =
    BodyMeasurement(
        id = NEW_ENTRY,
        weight = 78_400_000L,
        chest = 1_020,
        waist = 820,
        arm = 360,
        thigh = 580,
        bodyFat = 142,
        createdAt = PREVIEW_INSTANT,
        updatedAt = PREVIEW_INSTANT,
    )

/** A fixed instant, so the preview's date does not move with the wall clock. */
private const val PREVIEW_INSTANT = 1_777_000_000_000L
