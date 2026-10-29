package com.titan.core.ui.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A tap on nothing in particular puts the keyboard away.
 *
 * Android leaves a text field focused, and its keyboard up, until something else takes the
 * focus. On a screen of figures that is half the screen gone for as long as the lifter is not
 * typing: the IME's "Done" is the only way out, and it sits where the thumb is not. Tapping the
 * page beside the field is what people try first, so it works.
 *
 * Only a tap nothing else claimed: a button, a field or a tick consumes its own press first, so
 * pressing one never drops the focus out from under it. A drag or a scroll is not a tap either,
 * so moving through the list with the keyboard up leaves it up.
 *
 * Clearing the focus is the same move as the field's own "Done", so whatever a field does when
 * it is left — committing a corrected set, say — happens here too.
 *
 * @param focusManager the screen's `LocalFocusManager`, handed in rather than read here so this
 *   stays a plain modifier.
 */
fun Modifier.clearFocusOnTap(focusManager: FocusManager): Modifier =
    pointerInput(focusManager) {
        detectTapGestures(onTap = { focusManager.clearFocus() })
    }
