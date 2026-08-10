package com.opengym.core.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextOverflow
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The section header used throughout the wireframes — `THIS WEEK`, `APPEARANCE`,
 * `RESUME · 00:23:14`.
 *
 * Small, mono and widely tracked, it is what separates one block of a screen from the next
 * in a design that has no card shadows and few horizontal rules to do that job. It is
 * deliberately *not* a Material type role: `labelSmall` is a label on a control, and this is
 * a heading — screen readers and future type-scale changes should be able to tell them apart.
 *
 * **It uppercases for you**, in the composition's locale rather than invariantly, so a Turkish
 * user gets `İ` and not `I`. Call sites pass ordinary sentence-case text — passing
 * `"APPEARANCE"` still works but puts a shouted string in the source and, worse, in the
 * translation file, where a translator has no way to know the case is decoration.
 *
 * @param color defaults to `onSurfaceVariant`, the quiet role for a heading that must be
 *   findable without competing with the content under it. The accent is the deliberate
 *   exception — see the note on the accent in CONTRIBUTING.md — and is worth spending on the
 *   one section of a screen that matters right now.
 * @param maxLines how many lines it may wrap to. One by default, and clipped rather than
 *   ellipsised: an eyebrow is three or four words in a fixed column, and a heading that
 *   breaks `SET` across two lines is worse than one that is a pixel short.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = 1,
) {
    Text(
        text = text.uppercase(Locale.current.platformLocale),
        style = OpenGymTheme.typography.eyebrow,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Clip,
        modifier = modifier,
    )
}
