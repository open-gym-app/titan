package com.opengym.feature.history.sessions.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.opengym.core.ui.component.EmptyState
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.TemplateRow
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.sessions.SessionsUiState

/**
 * The saved templates.
 *
 * ### One card, unlike the history beside it
 *
 * A template list is a table of contents, not a log: it holds a handful of rows that all fit
 * on one screen, so the bordered card the design gives a group of related rows is affordable
 * here in a way it is not over an unbounded history. It is also what makes the two tabs read
 * as different *kinds* of list rather than as one list with different rows in it.
 *
 * ### The rows do nothing yet, and that is the honest shape
 *
 * Template authoring is #92. Until it lands there is nothing for a tap to open — starting a
 * workout from a template is the new-session flow's own picker, which is one tap away in the
 * `+ New` above — so the rows are drawn inert rather than given a ripple that leads nowhere.
 * #92 adds the tap and the overflow, in the slot [TemplateRow] already carries for it.
 */
@Composable
internal fun TemplateList(
    state: SessionsUiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = OpenGymTheme.spacing.screen),
    ) {
        if (state.templates.isEmpty()) {
            // Nothing while the read is in flight, for the reason the history draws nothing:
            // an empty state flashed over a list that is merely still opening tells a user
            // with six templates that they have none.
            if (!state.isLoadingTemplates) {
                item(key = EMPTY_KEY) { TemplatesEmptyState() }
            }
            return@LazyColumn
        }

        item(key = HEADER_KEY) {
            Spacer(modifier = Modifier.height(OpenGymTheme.spacing.section))
        }

        // One item holding every row, because they are one card. A template list is a few
        // dozen rows at most — see the note above — so this is not the unbounded composition
        // the history's per-row items exist to avoid.
        item(key = LIST_KEY) {
            OpenGymCard {
                state.templates.forEachIndexed { index, summary ->
                    if (index > 0) HairlineDivider()
                    TemplateRow(summary = summary)
                }
            }
        }

        item(key = FOOTER_KEY) {
            Spacer(modifier = Modifier.height(OpenGymTheme.spacing.section))
        }
    }
}

/**
 * What the tab says with nothing in it.
 *
 * No action, deliberately. The only way to make a template today is to finish a workout and
 * save it as one (#27), which is not a thing a button here can do — so the description says
 * where they come from rather than offering a way out that would not help. #92 gives this
 * state its `+ New`.
 */
@Composable
private fun TemplatesEmptyState(modifier: Modifier = Modifier) {
    EmptyState(
        glyph = stringResource(R.string.sessions_templates_glyph),
        title = stringResource(R.string.sessions_templates_empty_title),
        description = stringResource(R.string.sessions_templates_empty_description),
        modifier = modifier,
    )
}

/* Stable keys for the four items, none of which is a row — the rows are inside [LIST_KEY]. */
private const val EMPTY_KEY = "empty"
private const val HEADER_KEY = "header"
private const val LIST_KEY = "templates"
private const val FOOTER_KEY = "footer"
