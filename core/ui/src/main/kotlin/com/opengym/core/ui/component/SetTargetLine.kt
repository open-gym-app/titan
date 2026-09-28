package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.LoadSuggestion
import com.opengym.core.domain.model.PastPerformance
import com.opengym.core.domain.model.SuggestionReason
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.ui.R
import com.opengym.core.ui.format.formatSet
import com.opengym.core.ui.format.formatWeight
import com.opengym.core.ui.theme.MIN_TOUCH_TARGET
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * What the next set should be, and why, as one line a lifter can act on (#M7-16).
 *
 * `Target 82.5 kg × 8 · +2.5 kg`, with the rule that produced it under it in a sentence —
 * "Hit the top of the range last time — add 2.5 kg". The `PREV` column beside the entry row
 * says what happened last time; this is the answer to the question that column raises, which
 * is what to do about it now, and it sits under the same row for that reason.
 *
 * ### It is a button, and the fields above are its answer
 *
 * Tapping it fills the weight and the reps in. Nothing is logged: the tick is still the
 * lifter's to press, because a target is a proposal and a set is a fact, and the screen must
 * not turn one into the other on its own. The same figures are also shown as placeholders in
 * the fields — see `OpenGymValueField` — which is the *glance* form of this line; the tap is
 * what saves typing them.
 *
 * ### Spoken once, as a sentence
 *
 * The whole line is one node with the button role, described as the figures and then the
 * reason, and its action named. The placeholders in the fields are cleared from the tree so a
 * reader does not hear the same target twice on the way across the row, and the figures here
 * are formatted the long way — `82.5 kg × 8` — rather than the compact form the `PREV` cell
 * squeezes into 52dp, because a line has the room and a reader needs the unit.
 *
 * ### It wraps rather than clips
 *
 * The figures are one line at the default scale and may become two at twice it; the reason
 * is a sentence and wraps as one. Neither carries a `maxLines`, because the bar every screen
 * is held to is that nothing is clipped at 200 %, and a target ellipsised to `Target 82.5 kg ×`
 * is a wrong number with a confident face.
 *
 * @param target what the engine prescribes. Never drawn for a null one — the caller draws
 *   nothing, and the row looks exactly as it does without a suggestion.
 * @param onFill put the target's figures into the fields. The caller's event, since the
 *   fields are the caller's state.
 * @param color the ink, which is the caller's to choose because it depends on the ground: on
 *   the tinted entry row it is `onPrimaryContainer`, the one pair the contrast test holds.
 * @param enabled false leaves the line readable and inert — a paused workout takes no typing,
 *   and a tap that filled fields nothing can be typed into would be a promise the tick breaks.
 */
@Composable
fun SetTargetLine(
    target: LoadSuggestion,
    onFill: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    val headline = target.formattedTarget()
    val reason = target.reasonText()
    val spoken = stringResource(R.string.set_target_description, headline, reason)
    val fillAction = stringResource(R.string.set_target_fill_action)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(enabled = enabled, onClickLabel = fillAction, role = Role.Button, onClick = onFill)
            // After the clickable, so its role and its action stay; what goes is the two texts
            // under it, replaced by the one sentence a reader should hear. Merged rather than
            // cleared they would still ride along as text beside the description, which is
            // the target said twice by a reader that reads both.
            .clearAndSetSemantics { contentDescription = spoken }
            .heightIn(min = MIN_TOUCH_TARGET)
            .padding(horizontal = OpenGymTheme.spacing.xs, vertical = OpenGymTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Text(
                text = headline,
                style = OpenGymTheme.typography.numericSmall,
                color = color,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
        // The one mark that says the line does something: the fields it fills are the row
        // above, and the arrow points at them. Decorative to a reader, which has the action's
        // own label instead.
        Icon(
            imageVector = Icons.Outlined.ArrowUpward,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(FILL_GLYPH_SIZE),
        )
    }
}

/**
 * The prescription as a set reads anywhere else: `82.5 kg × 8`, or `9 reps` for a bodyweight
 * movement. The figures alone, with nothing said about where they came from.
 *
 * Public because the exercise detail's card draws the same figures under a heading that
 * already names them, where [formattedTarget]'s `Target` prefix would be that heading said
 * twice — and because two call sites deriving one set's text is how they come to disagree
 * about a bodyweight movement.
 */
@Composable
fun LoadSuggestion.formattedFigures(): String =
    // Never null: a suggestion always prescribes at least one rep, so `formatSet` always has
    // something to put on the line. The fallback is for the type system, not for a case.
    formatSet(weight, reps) ?: reps.toString()

/**
 * The target as its figures: `Target 82.5 kg × 8 · +2.5 kg`, or `Target 9 reps` for a
 * bodyweight movement, or `Target 72 kg × 3 · −8 kg` for a deload.
 *
 * The change is left off when there is none to show — an add-rep at the same weight, or a
 * movement with no weight to move — rather than drawn as `+0 kg`, which reads as a step of
 * nothing.
 */
@Composable
private fun LoadSuggestion.formattedTarget(): String {
    val line = stringResource(R.string.set_target_line, formattedFigures())
    val change = formattedChange() ?: return line
    return stringResource(R.string.set_target_with_change, line, change)
}

/**
 * How far the weight moves, with its sign: `+2.5 kg`, `−8 kg` — or null for no move at all,
 * either because the weight is the same or because there is no weight.
 */
@Composable
@ReadOnlyComposable
private fun LoadSuggestion.formattedChange(): String? {
    val change = weightChange?.takeIf { it != 0L } ?: return null
    return if (change > 0) {
        stringResource(R.string.set_target_gain, formatWeight(change))
    } else {
        stringResource(R.string.set_target_loss, formatWeight(-change))
    }
}

/**
 * Why the target is what it is, as one line of advice: *"Hit the top of the range last time —
 * add 2.5 kg"*. The screen's wording for [SuggestionReason], which `:core:domain` cannot hold.
 *
 * Written as what to do rather than as the rule's name, per the issue: a lifter with a bar in
 * their hands wants the instruction, and "double progression, increase branch" is a fact about
 * the engine. The increase and the deload quote the step, since that is the number the advice
 * turns on; the bodyweight deload has nothing to take off and says what to do instead.
 */
@Composable
@ReadOnlyComposable
fun LoadSuggestion.reasonText(): String =
    when (reason) {
        // Never null: an increase always moves between two weights — see `LoadSuggestion`.
        SuggestionReason.INCREASE ->
            stringResource(R.string.suggestion_reason_increase, formatWeight(checkNotNull(weightChange)))

        SuggestionReason.ADD_REP -> stringResource(R.string.suggestion_reason_add_rep)

        SuggestionReason.REPEAT -> stringResource(R.string.suggestion_reason_repeat)

        SuggestionReason.DELOAD -> {
            val step = weightChange?.takeIf { it < 0L }
            if (step != null) {
                stringResource(R.string.suggestion_reason_deload, formatWeight(-step))
            } else {
                stringResource(R.string.suggestion_reason_deload_bodyweight)
            }
        }
    }

/**
 * The glyph's size: under the 24dp an icon button draws, because it is a mark on a line of
 * `numericSmall` text rather than a control of its own — the whole line is the control.
 */
private val FILL_GLYPH_SIZE: Dp = 18.dp

/** Five lines, each wrapped to three or four at 2x, with the card's padding around them. */
private const val LARGE_FONT_PREVIEW_HEIGHT_DP = 900

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Set target - the four reasons", widthDp = 360)
@Composable
fun SetTargetLinePreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        TargetLines()
    }
}

@Preview(name = "Set target - the four reasons, dark", widthDp = 360)
@Composable
fun SetTargetLineDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        TargetLines()
    }
}

// Tall enough to hold every line wrapped at twice the size; the default height would cut the
// picture off under the third, which is not a finding about the lines.
@Preview(name = "Set target - twice the font", widthDp = 320, heightDp = LARGE_FONT_PREVIEW_HEIGHT_DP, fontScale = 2f)
@Composable
fun SetTargetLineLargeFontPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        TargetLines()
    }
}

/** One of each reason, on the ground the entry row draws them on. */
@Composable
private fun TargetLines() {
    OpenGymCard(modifier = Modifier.fillMaxWidth().padding(OpenGymTheme.spacing.screen)) {
        Column(
            modifier = Modifier.padding(OpenGymTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
        ) {
            previewTargets().forEach { target ->
                SetTargetLine(
                    target = target,
                    onFill = {},
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraSmall)
                        .padding(OpenGymTheme.spacing.xs),
                )
            }
        }
    }
}

/** An increase, an add-rep, a repeat and a deload, off one 80 kg × 6 × 3 bench session. */
private fun previewTargets(): List<LoadSuggestion> {
    val benched = PastPerformance(List(3) { index -> previewTargetSet(index + 1, 80_000_000L, 6) })
    val pressed = PastPerformance(List(3) { index -> previewTargetSet(index + 1, null, 8) })
    return listOf(
        LoadSuggestion(
            weight = 82_500_000L,
            reps = 3,
            reason = SuggestionReason.INCREASE,
            previousWeight = 80_000_000L,
            basis = benched,
        ),
        LoadSuggestion(
            weight = 80_000_000L,
            reps = 7,
            reason = SuggestionReason.ADD_REP,
            previousWeight = 80_000_000L,
            basis = benched,
        ),
        LoadSuggestion(
            weight = 80_000_000L,
            reps = 6,
            reason = SuggestionReason.REPEAT,
            previousWeight = 80_000_000L,
            basis = benched,
        ),
        LoadSuggestion(
            weight = 72_500_000L,
            reps = 3,
            reason = SuggestionReason.DELOAD,
            previousWeight = 80_000_000L,
            basis = benched,
        ),
        LoadSuggestion(
            weight = null,
            reps = 9,
            reason = SuggestionReason.ADD_REP,
            previousWeight = null,
            basis = pressed,
        ),
    )
}

private fun previewTargetSet(
    setNumber: Int,
    weight: Long?,
    reps: Int,
) = WorkoutSet(
    sessionExerciseId = 1,
    setNumber = setNumber,
    reps = reps,
    weight = weight,
    isCompleted = true,
    completedAt = 0,
    createdAt = 0,
    updatedAt = 0,
)
