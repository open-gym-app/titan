package com.opengym.feature.history.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The `+ New` at the end of a tab's header — a new session, a new exercise.
 *
 * A filled button, where this used to be an [com.opengym.core.ui.component.Eyebrow] inside a
 * `TextButton`. Two things were wrong with that and they compounded: an eyebrow is 10sp of
 * widely tracked mono, which is the size of a *heading* over a block of content, and a bare
 * label with no container in the corner of a busy row does not read as something to press at
 * all. Squeezed between two 24dp icons, `+ NEW` was the smallest thing on the screen and the
 * only part of it that did anything.
 *
 * So it takes the accent and a container. That is a lot of red for a header, and it is the
 * right amount for this one: starting a session is what the tab is *for*, and every other
 * control on the row — search, dates, filters — only rearranges what is already there.
 *
 * Drawn to the height of the glyphs beside it rather than to a button's own 40dp, so the row
 * is one line of controls of one size instead of a filled shape with two icons parked against
 * it. The cost is the touch target — see [HEIGHT].
 *
 * Shared by both headers because they are the same control twice, and a header that grew its
 * own version of it is how two tabs stop looking like one app.
 *
 * @param label what it makes, sentence case — `New`. Not uppercased: this is a button, and the
 *   shouted case belongs to the headings around it.
 * @param description what it does, for the announcement. A merged node announces its
 *   `contentDescription` in place of the text under it, so TalkBack can say "New session" where
 *   the row only has room for "New".
 */
@Composable
internal fun NewButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        // Vertically nothing, because the height below is exact and a button's default padding
        // is laid out for the 40dp shape it is not being drawn at.
        contentPadding = PaddingValues(horizontal = OpenGymTheme.spacing.md, vertical = 0.dp),
        modifier = modifier
            .height(HEIGHT)
            .semantics { contentDescription = description },
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
        )
        Spacer(modifier = Modifier.width(OpenGymTheme.spacing.xs))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * As tall as the glyph in the icon buttons beside it, which is what puts the row on one line.
 *
 * Under the 48dp a touch target is supposed to be, and knowingly: the button is wide, it sits
 * in the corner where nothing else is pressable, and the row it belongs to would otherwise be a
 * 40dp shape flanked by two 24dp marks. The same action is reachable at full size from the
 * empty state and, on the session tabs, from `START SESSION` on the home screen.
 */
private val HEIGHT: Dp = 24.dp

/** The `+`, sized to leave the glyph some air inside a row [HEIGHT] tall. */
private val ICON_SIZE: Dp = 16.dp
