package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.intl.Locale
import com.titan.core.ui.theme.PillShape
import com.titan.core.ui.theme.TitanTheme

/**
 * A small capsule of text against a filled ground: `PR`, `SOON`, `DROP SET`, `3× WEEK`.
 *
 * The wireframes' chip, minus the interaction — this is a *label*, not a filter, so it has no
 * click, no selected state and no semantics of its own beyond the text it shows. Material's
 * `AssistChip` and `FilterChip` are the controls; reaching for one to render a static tag
 * gives it a 32dp minimum touch target and a role that reads to a screen reader as something
 * you can press.
 *
 * The text is uppercased in the device's locale for the same reason [Eyebrow]'s is: it is
 * typographic treatment, not content, and a translation file should not have to carry it.
 */
@Composable
fun TitanBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.NEUTRAL,
) {
    val container: Color = when (tone) {
        BadgeTone.ACCENT -> MaterialTheme.colorScheme.primaryContainer
        BadgeTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHigh
        BadgeTone.CRITICAL -> MaterialTheme.colorScheme.errorContainer
    }
    val content: Color = when (tone) {
        BadgeTone.ACCENT -> MaterialTheme.colorScheme.onPrimaryContainer
        BadgeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
        BadgeTone.CRITICAL -> MaterialTheme.colorScheme.onErrorContainer
    }

    Text(
        text = text.uppercase(Locale.current.platformLocale),
        style = TitanTheme.typography.eyebrow,
        color = content,
        // One line, always. A badge is a label rather than prose: wrapped, `HYPERTROPHY` comes
        // out as a three-line lozenge that is taller than the row it is on, and what it says is
        // no clearer for it. Where the room is not there, the thing beside it is what gives.
        maxLines = 1,
        modifier = modifier
            .background(color = container, shape = PillShape)
            .padding(horizontal = TitanTheme.spacing.sm, vertical = TitanTheme.spacing.xs),
    )
}
