package com.titan.core.ui.component

import java.text.BreakIterator
import java.util.Locale

/**
 * The opening letter of a name, uppercased — the mark drawn where a picture would go.
 *
 * Two components fall back to it, for two things the user named themselves: an exercise with
 * no bundled photo ([ExerciseImage]) and a saved template, which has no imagery at all
 * ([TemplateRow]). One function because it is one rule, and because the two would otherwise
 * disagree about the paragraph below the first time either was touched.
 *
 * The first **grapheme**, not the first `Char`. These names are user-authored, and
 * `🏋️ Bench` opens with a surrogate pair followed by a variation selector: `take(1)` cuts
 * the pair in half and draws tofu. [BreakIterator] is the thing that knows where a character
 * ends, and it is locale-aware, as is the uppercasing — so `ıskele` comes out `I` in most
 * locales and `İ` in a Turkish one.
 *
 * A name that is empty or all whitespace — which neither domain model forbids — gives
 * [NO_INITIAL] rather than an empty string, so the box it sits in is never blank.
 */
internal fun String.initialGrapheme(): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return NO_INITIAL

    val graphemes = BreakIterator.getCharacterInstance(Locale.getDefault())
    graphemes.setText(trimmed)
    return trimmed.substring(0, graphemes.next()).uppercase(Locale.getDefault())
}

/** Shown for a name that is empty or all whitespace. */
private const val NO_INITIAL = "?"
