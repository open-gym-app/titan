package com.opengym.core.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.component.StatChip
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The `↑ 18%` beside a hero figure: an arrow, a magnitude, and nothing else.
 *
 * ### The accent is spent on a rise and on nothing else
 *
 * A rise takes the accented pill; a fall and a flat period take the neutral one. **Not the
 * error role** — a lighter month is not a fault, and painting it in the colour this app
 * reserves for something lost or overdue would be the app telling a lifter they failed at a
 * deload. The arrow says which way it went; the colour says whether it is the thing to look at.
 *
 * ### The glyph is drawn, the words are spoken
 *
 * `↑ 18%` announced literally is an arrow character followed by a number, which is not a
 * sentence. The chip carries `Up 18%` as its description instead — see [StatChip]'s
 * `description`, which exists for this.
 *
 * @param direction which way it went. [ChangeDirection.NONE] draws the magnitude with no arrow.
 * @param value the magnitude, already formatted and unsigned: `18%`, `4.5 kg`. Unsigned because
 *   the arrow is the sign, and `↑ -18%` is two of them disagreeing.
 */
@Composable
fun ChangeChip(
    direction: ChangeDirection,
    value: String,
    modifier: Modifier = Modifier,
) {
    val text = when (direction) {
        ChangeDirection.UP -> stringResource(R.string.chart_change_up, value)
        ChangeDirection.DOWN -> stringResource(R.string.chart_change_down, value)
        ChangeDirection.NONE -> value
    }
    val spoken = when (direction) {
        ChangeDirection.UP -> stringResource(R.string.chart_change_up_description, value)
        ChangeDirection.DOWN -> stringResource(R.string.chart_change_down_description, value)
        ChangeDirection.NONE -> stringResource(R.string.chart_change_none_description, value)
    }

    StatChip(
        value = text,
        modifier = modifier,
        accented = direction == ChangeDirection.UP,
        description = spoken,
    )
}

/** The same chip from a [ChartChange] a caller is already carrying. See [SeriesHeroCard]. */
@Composable
fun ChangeChip(
    change: ChartChange,
    modifier: Modifier = Modifier,
) {
    ChangeChip(direction = change.direction, value = change.value, modifier = modifier)
}

/**
 * How far a hero figure moved against the period before it: the arrow, and the magnitude.
 *
 * One value rather than two parameters on [SeriesHeroCard], because a direction with no
 * magnitude and a magnitude with no direction are both halves of a chip nobody can draw — and
 * a card whose change is *absent* (a first period, with nothing behind it to compare against)
 * says so by passing null once instead of by two nullables that have to agree.
 *
 * @param value already formatted and **unsigned**: `18%`, `1.2 kg`. See [ChangeChip].
 */
@Immutable
data class ChartChange(
    val direction: ChangeDirection,
    val value: String,
)

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Change chip", widthDp = 360)
@Composable
fun ChangeChipPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ChangeChipCatalogue()
    }
}

@Preview(name = "Change chip, dark", widthDp = 360)
@Composable
fun ChangeChipDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        ChangeChipCatalogue()
    }
}

@Composable
private fun ChangeChipCatalogue() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        ChangeChip(direction = ChangeDirection.UP, value = "18%")
        ChangeChip(direction = ChangeDirection.DOWN, value = "4%")
        ChangeChip(direction = ChangeDirection.NONE, value = "0%")
    }
}
