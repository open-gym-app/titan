package com.titan.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.R
import com.titan.core.ui.theme.TitanTheme

/**
 * The search box the browsing screens open with — a glyph, a line of text, and a way to
 * clear it.
 *
 * Not Material's `SearchBar`, and not an `OutlinedTextField`. `SearchBar` is a whole
 * expanding surface that takes the screen over and owns a results slot; this design searches
 * *in place*, with the list and its filters staying where they were. `TextField` in either
 * flavour brings a 56dp container, a floating label and Material's own container colours,
 * which is three things to override and a height this layout has not got to spare above two
 * rows of chips.
 *
 * So: a [BasicTextField] inside an [TitanCard], the same bordered surface every other box
 * on the screen is drawn as.
 *
 * The field is **fully hoisted and single-line**. One that kept its own text would be a
 * second copy of the query the caller is already filtering on, and the two would disagree
 * the first time the query was cleared from anywhere but here.
 *
 * @param onSearch the keyboard's search key. Null leaves it dismissing the keyboard and
 *   nothing else, which is right where results update as the user types.
 * @param focusRequester a handle on the caret, for a box that is *opened* rather than always
 *   there: a screen that reveals this from a search button has to be able to put the cursor
 *   in it, or the user taps twice to type once. It goes on the text field itself rather than
 *   being left to the caller's [modifier], which lands on the surface around it and has no
 *   focus target to give.
 * @param trailing drawn after the clear button — a sort or filter affordance. Nothing by
 *   default.
 */
@Composable
fun TitanSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search_placeholder),
    onSearch: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    TitanCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) {
        Row(
            modifier = Modifier.padding(
                start = TitanTheme.spacing.lg,
                end = TitanTheme.spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                // The field beside it is already labelled; a screen reader announcing
                // "search" twice per box is reading the decoration.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ICON_SIZE),
            )

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = TitanTheme.spacing.lg)
                    .then(
                        focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
                    ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        onSearch?.invoke()
                    },
                ),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        field()
                    }
                },
            )

            // Present only when there is something to clear. A permanently visible clear
            // button on an empty field is a control that does nothing, and it reads as one.
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.search_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(ICON_SIZE),
                    )
                }
            }

            trailing?.invoke()
        }
    }
}

/** Sized to the body text beside it rather than to Material's 24dp default. */
private val ICON_SIZE = 18.dp

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Search field", widthDp = 360)
@Composable
fun TitanSearchFieldPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        SearchFieldCatalogue()
    }
}

@Preview(name = "Search field, dark", widthDp = 360)
@Composable
fun TitanSearchFieldDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        SearchFieldCatalogue()
    }
}

@Composable
private fun SearchFieldCatalogue() {
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(TitanTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        TitanSearchField(query = "", onQueryChange = {})
        TitanSearchField(query = "Bench", onQueryChange = {})
    }
}
