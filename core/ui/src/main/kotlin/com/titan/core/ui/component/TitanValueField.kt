package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.titan.core.ui.theme.HAIRLINE_WIDTH
import com.titan.core.ui.theme.TitanTheme

/**
 * The app's numeric cell: a filled box holding one figure, and nothing else.
 *
 * [BasicTextField] rather than `OutlinedTextField`: Material's text fields carry a floating
 * label, a 56dp minimum height and their own container, and four of them across a 300dp row
 * is not a layout. What is kept is what the field is *for* — a numeric keyboard, a single
 * line, a visible focus and error state, and a label a screen reader can read.
 *
 * Two screens are built out of these and they have to be the same cell: [SetLogRow], where the
 * figure is a set being logged, and the template editor, where it is the default that set will
 * start from. A second implementation is how a weight typed in a template ends up looking like a
 * different kind of thing from the weight it becomes.
 *
 * [error] is carried on the field rather than on any text drawn near it, because a `Text` with
 * no role is a node TalkBack never stops on: an error announced there is an error nobody hears.
 * On the field it is read out with the value that was refused.
 *
 * [onDone] turns the keyboard's action key into DONE and is what it does. Null leaves the key
 * whatever the platform would have made it, which is what a field nobody has to leave wants.
 *
 * @param caption drawn under the box in the eyebrow role — `SETS`, `REPS`, `KG`. For a field
 *   standing on its own, where there is no column header above it saying which figure this is.
 *   Null in a table, which has one.
 * @param placeholder what the box shows while it is empty — a suggested value, drawn in the
 *   same figure role as a typed one but in the quieter ink, so it reads as an answer offered
 *   rather than one given. It is **not** the value: the first keystroke replaces it, and a
 *   field left empty stays empty. Nor is it announced — a screen reader hears the field's
 *   [description] and its (empty) contents, because whatever offered the figure is expected to
 *   say it once, in a sentence, somewhere the reader can act on it; see `SetTargetLine`.
 */
@Composable
fun TitanValueField(
    value: String,
    onValueChange: (String) -> Unit,
    description: String,
    keyboardType: KeyboardType,
    enabled: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    caption: String? = null,
    placeholder: String? = null,
    onDone: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.extraSmall
    val border = if (error != null) MaterialTheme.colorScheme.error else Color.Transparent

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = !enabled,
            singleLine = true,
            textStyle = TitanTheme.typography.numeric.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = onDone?.let { { it() } }),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            // `fillMaxWidth` rather than the caller's modifier, which sizes the whole cell:
            // the box has to occupy the column it was given, or a weighted table column comes
            // out as a narrow box floating in the middle of it.
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = description
                    if (error != null) this.error(error)
                },
            decorationBox = { field ->
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                        .border(HAIRLINE_WIDTH, border, shape)
                        .padding(vertical = TitanTheme.spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    // Under the field rather than in place of it, so the cursor and the
                    // keyboard still land on the box the figure will go in.
                    if (placeholder != null && value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TitanTheme.typography.numeric,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clearAndSetSemantics { },
                        )
                    }
                    field()
                }
            },
        )

        // Cleared from the tree: the field above it already announces which figure it holds, and
        // a caption read out after the value is the column heading said twice.
        if (caption != null) {
            Eyebrow(
                text = caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}
