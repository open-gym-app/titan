package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.R
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * What kind of set this is, picked from the cell that already shows the answer.
 *
 * ### Why the leading cell, and not a row of chips
 *
 * Because the cell is where the answer is *read*. A marked set draws its initial in place of its
 * number — `W`, `D`, `F`, `I`, see [SetMarker] — so the control and its own output are the same
 * 28dp of screen, and choosing a type costs no layout at all.
 *
 * What this replaces was a scrolling row of five chips under the row being typed. Five labels do
 * not fit across a 360dp screen, so it scrolled, which hides two of the five behind a gesture; and
 * it was drawn on every exercise of the workout, which put a third row of chrome between each pair
 * of set tables on a page whose whole job is that the workout can be read at a glance. A closed
 * vocabulary of five, picked rarely, is what a menu is for.
 *
 * ### The affordance
 *
 * An editable cell is drawn on the same faint fill the weight and rep fields use, so the leading
 * cell of a row that can be typed into looks like the rest of that row's controls. Once a type is
 * set, the chip's own tint takes that ground over — which is the whole point of the tints, and is
 * why there is no chevron: at this width a chevron costs a character of the initial it is pointing
 * at.
 *
 * @param setNumber the set's place in its table, from 1. Shown when the type is [SetType.NORMAL],
 *   and named in what a screen reader says either way, so it says *which* set is being marked.
 * @param selected the type currently set, which is [SetType.NORMAL] for a set nobody has marked.
 *   There is no null: every set has a type, and "not marked" *is* the standard set.
 * @param onSelect the type picked. Emitted for the already-selected item too, which a caller may
 *   treat as a no-op — the menu closes either way, which is what the tap was for.
 * @param isActive whether this is the row being worked. Passed through to [SetMarker], and only
 *   affects the number.
 * @param enabled false leaves the cell readable and inert — a paused or finished session, whose
 *   writes the domain refuses anyway. The menu cannot be opened, so there is no state to hold
 *   closed.
 */
@Composable
fun SetTypePicker(
    setNumber: Int,
    selected: SetType,
    onSelect: (SetType) -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }

    // `enabled` closes the menu as well as withholding it. A menu is a popup, so one opened just
    // before the workout was paused would otherwise stay up over a card that can no longer be
    // written to — and take a value out of the one gap in the read-only state. It is *closed*
    // rather than hidden: leaving the flag set behind a hidden popup is a menu that springs back
    // open over a row nobody touched, the moment the workout resumes.
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }
    val spoken = stringResource(R.string.set_type_field, setNumber, selected.label())

    Box(
        modifier = modifier
            .width(INDEX_COLUMN_WIDTH)
            .heightIn(min = PICKER_MIN_HEIGHT)
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(
                enabled = enabled,
                role = Role.DropdownList,
                onClick = { expanded = true },
            )
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        // The unmarked case gets the fields' own fill, so the cell reads as a control before it
        // has been used. A marked one already has a ground — its tint — and a second one under it
        // would be two squares of nearly the same size in the same cell.
        Box(
            modifier = Modifier
                .size(MARKER_SIZE)
                .background(
                    color = if (selected == SetType.NORMAL && enabled) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        Color.Transparent
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                ),
            contentAlignment = Alignment.Center,
        ) {
            SetMarker(setNumber = setNumber, setType = selected, isActive = isActive)
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SetType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(text = type.label(), style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        expanded = false
                        onSelect(type)
                    },
                    // The chip the row will carry, so the menu is read in the language the table
                    // is written in rather than in words alone.
                    leadingIcon = {
                        SetTypeChip(setType = type, modifier = Modifier.width(INDEX_COLUMN_WIDTH))
                    },
                    trailingIcon = {
                        if (type == selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * How tall the tappable cell is.
 *
 * Matched to the height a `SetValueField` comes out at rather than to the 24dp chip inside it, so
 * the leading cell of a row is as easy to hit as the field next to it. The row is not tall enough
 * for the 48dp a standalone control would take — it is a table, on purpose — and the whole logged
 * row is a second, larger target for the same set.
 */
private val PICKER_MIN_HEIGHT: Dp = 40.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Set type picker", widthDp = 360)
@Composable
fun SetTypePickerPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        SetTypePickerCatalogue()
    }
}

@Preview(name = "Set type picker, dark", widthDp = 360)
@Composable
fun SetTypePickerDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        SetTypePickerCatalogue()
    }
}

/** All five types as a row's leading cell draws them, each beside the word it stands for. */
@Composable
private fun SetTypePickerCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(OpenGymTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
    ) {
        SetType.entries.forEachIndexed { index, type ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SetTypePicker(setNumber = index + 1, selected = type, onSelect = {})
                Text(
                    text = type.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
