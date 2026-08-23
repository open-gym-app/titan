package com.opengym.core.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
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
 * **What it hands a screen reader is the sentence-case original.** TalkBack reads an
 * all-capitals word as an initialism and may spell it out — `START EMPTY SESSION` letter by
 * letter is not an action anybody can follow — so the uppercase stays where it belongs, in
 * the pixels. This is set here rather than at each call site because every eyebrow in the app
 * has the problem: the bottom bar, which draws its own uppercase label rather than using this,
 * had to work around it by hand.
 *
 * **A container that merges its descendants has to speak for itself.** A merged node carrying
 * a description anywhere inside it is announced as that description and nothing else, so an
 * eyebrow dropped into a `clickable` card silences the card's other text. Home's resume banner
 * is the worked example: it carries a sentence of its own and clears the eyebrow's, so the
 * workout's name and the line saying where it was left are still spoken.
 *
 * @param color defaults to `onSurfaceVariant`, the quiet role for a heading that must be
 *   findable without competing with the content under it. The accent is the deliberate
 *   exception — see the note on the accent in CONTRIBUTING.md — and is worth spending on the
 *   one section of a screen that matters right now.
 * @param textAlign how it sits in the width it was given. Null — the default — leaves it at the
 *   start, which is what a heading over a block of content wants. Centre is for the one job an
 *   eyebrow does that is not a heading: labelling a *column*, where the label has to sit over the
 *   cells rather than over the left edge of the space they share. See `SetLogHeader`.
 * @param maxLines how many lines it may wrap to. One by default, and clipped rather than
 *   ellipsised: an eyebrow is three or four words in a fixed column, and a heading that
 *   breaks `SET` across two lines is worse than one that is a pixel short.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
) {
    Text(
        text = text.uppercase(Locale.current.platformLocale),
        style = OpenGymTheme.typography.eyebrow,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Clip,
        // The description rides on the text's own node, so a call site that speaks for its
        // whole contents — the cards that `clearAndSetSemantics` — still wipes it along with
        // everything else in there, which is what those call sites want.
        modifier = modifier.semantics { contentDescription = text },
    )
}
