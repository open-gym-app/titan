package com.opengym.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import com.opengym.core.ui.theme.HAIRLINE_WIDTH
import com.opengym.core.ui.theme.OpenGymTheme

/**
 * The one-line field the app's naming dialogs are built from: a bordered box with a cursor in it.
 *
 * ### Why it is not an `OutlinedTextField`
 *
 * Material's does not settle inside an `AlertDialog`. The dialog sizes itself from its content's
 * *intrinsic* width and the outlined field's decoration reports one that depends on the width it
 * is given, so the two remeasure each other forever: Compose never reaches idle, and under
 * Robolectric the test runs out of heap rather than failing. What a field is *for* — a keyboard,
 * a single line, a visible focus, a label a screen reader can read and an error it can hear —
 * all survives dropping the container.
 *
 * Dropping `OutlinedTextField` was not the end of that story — see [OpenGymDialog], which both of
 * this field's callers now use instead of `AlertDialog`. This field's own `fillMaxWidth` keeps it
 * from asking its *own* question about width, but `AlertDialog` was still free to ask any of its
 * *other* children the same question, and at a real phone's window size and density (#187) it did.
 *
 * `fillMaxWidth` on the box rather than on the field, so a dialog's width is decided by its title
 * and its buttons rather than by how long the name happens to be: a field that grew as the user
 * typed would move the confirm button out from under their thumb.
 *
 * @param label what a screen reader reads for the field. There is no drawn label — the dialog's
 *   title is what names it.
 * @param error the reason the value was refused, or null. Set on the field itself rather than on
 *   the text under it, because TalkBack never stops on a `Text` with no role and an error
 *   announced there is an error nobody hears. The *visible* copy is the caller's to draw.
 */
@Composable
internal fun DialogNameField(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
) {
    val shape = MaterialTheme.shapes.small
    val borderColor = if (error != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    BasicTextField(
        value = name,
        onValueChange = onNameChange,
        enabled = enabled,
        readOnly = !enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        // The keyboard's own confirm does what the button does. A one-field dialog where
        // "done" only dismisses the keyboard is one the user presses twice.
        keyboardActions = KeyboardActions(onDone = { onConfirm() }),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                if (error != null) this.error(error)
            },
        decorationBox = { field ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                    .border(HAIRLINE_WIDTH, borderColor, shape)
                    .padding(horizontal = OpenGymTheme.spacing.lg, vertical = OpenGymTheme.spacing.xl),
            ) {
                if (name.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        // A placeholder is text, and text on this field's deeper fill needs
                        // 4.5:1, which `outline` — a control's edge, held to 3:1 — does not
                        // give it in either scheme. One step quieter than what is typed, and
                        // no quieter.
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                field()
            }
        },
    )
}
