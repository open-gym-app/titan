package com.titan.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A dialog shaped like Material3's `AlertDialog` — title, body, two buttons — without the one
 * thing that makes `AlertDialog` unusable with a text field in it: sizing itself from its
 * content's *intrinsic* width.
 *
 * ### Why this exists instead of `AlertDialog`
 *
 * `AlertDialog` measures its content twice — once to ask "how wide do you want to be", again to
 * lay it out at whatever width that settled on — and a field inside it can be asked to answer
 * the first question with an answer that depends on the width it is given, which is exactly what
 * a text field measured by real `StaticLayout` line-breaking does. The two measurements chase
 * each other, and depending on the window's size and density that chase never ends: composition
 * genuinely never reaches idle, which is invisible on a device (one wasted frame or two) and
 * fatal under Robolectric, whose tests wait for idle before every read and time out rather than
 * fail.
 *
 * `DialogNameField`'s own doc already tells this story once for `OutlinedTextField`, whose
 * *decoration* reports an intrinsic width. Swapping in `BasicTextField` there fixed that one
 * case, but the outer `AlertDialog` was still free to ask *any* child for its intrinsic width,
 * and at the window a real phone actually opens the app at — confirmed against
 * `w411dp-h891dp-xxhdpi`, the size `:app`'s own tests already drive the shell at — it does, and
 * the chase resumes one level up. This component removes the question instead of answering it
 * faster: [Surface] is given [Modifier.fillMaxWidth], a width that comes from the incoming
 * constraints and never from what is measured inside it, so there is no second measurement for
 * anything inside to disagree with.
 *
 * @param title See `AlertDialog`.
 * @param text See `AlertDialog`.
 * @param confirmButton See `AlertDialog`.
 * @param dismissButton See `AlertDialog`.
 * @param shape See `AlertDialog`.
 */
@Composable
fun TitanDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        // The platform's own default width is itself intrinsic — computed from the window's
        // content the same way `AlertDialog`'s is — so it is turned off here for the same
        // reason `AlertDialog` is not used at all: `fillMaxWidth` below needs constraints that
        // do not already depend on what is inside them.
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = DIALOG_MARGIN),
            shape = shape,
            color = containerColor,
            tonalElevation = DIALOG_ELEVATION,
        ) {
            Column(modifier = Modifier.padding(DIALOG_PADDING)) {
                if (title != null) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                            title()
                        }
                    }
                    Spacer(Modifier.height(TITLE_TO_TEXT_GAP))
                }
                if (text != null) {
                    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                        CompositionLocalProvider(LocalContentColor provides textColor) {
                            text()
                        }
                    }
                    Spacer(Modifier.height(TEXT_TO_BUTTONS_GAP))
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    if (dismissButton != null) {
                        dismissButton()
                        Spacer(Modifier.width(BUTTON_GAP))
                    }
                    confirmButton()
                }
            }
        }
    }
}

/** `AlertDialog`'s own default margin from the edge of the window. */
private val DIALOG_MARGIN = 24.dp

/** `AlertDialog`'s own default padding between its border and its content. */
private val DIALOG_PADDING = 24.dp

private val TITLE_TO_TEXT_GAP = 16.dp
private val TEXT_TO_BUTTONS_GAP = 24.dp
private val BUTTON_GAP = 8.dp

/** `AlertDialog`'s own default tonal elevation. */
private val DIALOG_ELEVATION = 6.dp
