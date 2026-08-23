package com.opengym.feature.history.sessions.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.ui.component.EmptyState
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.TemplateRow
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.history.R
import com.opengym.feature.history.sessions.SessionsEvent
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
 * ### A tap opens it, and the three things you can do *to* it are behind the ⋮
 *
 * Tapping a row opens the editor, because reading what a template contains is the commonest
 * reason to touch one and it is also the way to change it. Rename, duplicate and delete sit in
 * an overflow menu rather than on the row: they are three controls per row on a list whose
 * whole job is to be scanned, and two of them are things a user does once in the life of a
 * template.
 *
 * The menu's own state is local. Which row has its menu open is not something anything outside
 * this list acts on, and a ViewModel holding it would be carrying an id it could never use.
 *
 * @param onTemplateClick open a template's editor. Hoisted through the screen, because the
 *   destination is `:app`'s to name — see `historyGraph`.
 * @param onNewTemplate open the same editor on nothing. Hoisted for the same reason.
 */
@Composable
internal fun TemplateList(
    state: SessionsUiState,
    listState: LazyListState,
    onEvent: (SessionsEvent) -> Unit,
    onTemplateClick: (templateId: Long) -> Unit,
    onNewTemplate: () -> Unit,
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
                item(key = EMPTY_KEY) { TemplatesEmptyState(onNewTemplate = onNewTemplate) }
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
                    TemplateRow(
                        summary = summary,
                        onClick = { onTemplateClick(summary.template.id) },
                        trailing = { TemplateMenu(summary = summary, onEvent = onEvent) },
                    )
                }
            }
        }

        item(key = FOOTER_KEY) {
            Spacer(modifier = Modifier.height(OpenGymTheme.spacing.section))
        }
    }
}

/**
 * The ⋮ on a template row, and what is under it.
 *
 * Delete is last and drawn in `error`, which is the only place this screen spends that colour:
 * it is the one item in the menu that cannot be taken back by pressing the same thing again.
 * It still asks first — see `TemplateAction.Deleting` — because a template is a plan the user
 * built rather than a row of data, and the undo an immediate delete would need is a whole
 * template held in memory until a snackbar times out.
 */
@Composable
private fun TemplateMenu(summary: TemplateSummary, onEvent: (SessionsEvent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val templateId = summary.template.id
    // Resolved here rather than in the ViewModel, which has no `Context` — see
    // `SessionsEvent.TemplateDuplicateRequested`.
    val copyName = stringResource(R.string.sessions_template_copy_name, summary.template.name)

    // Narrower than Material's 48dp default, which on a 320dp screen is a sixth of the row: the
    // glyph is 18dp and what it costs the line beside it is the template's estimate. The touch
    // target stays a comfortable 36dp square with the row's own padding around it.
    IconButton(onClick = { expanded = true }, modifier = Modifier.size(MENU_SIZE)) {
        Icon(
            imageVector = Icons.Outlined.MoreVert,
            contentDescription = stringResource(
                R.string.sessions_template_actions,
                summary.template.name,
            ),
            modifier = Modifier.size(MENU_GLYPH_SIZE),
        )
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sessions_template_rename)) },
            onClick = {
                expanded = false
                onEvent(SessionsEvent.TemplateRenameRequested(templateId))
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sessions_template_duplicate)) },
            onClick = {
                expanded = false
                onEvent(SessionsEvent.TemplateDuplicateRequested(templateId, copyName))
            },
        )
        DropdownMenuItem(
            text = {
                Text(
                    text = stringResource(R.string.sessions_template_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {
                expanded = false
                onEvent(SessionsEvent.TemplateDeleteRequested(templateId))
            },
        )
    }
}

/**
 * What the tab says with nothing in it.
 *
 * The action opens the editor on a blank template, which is the thing this screen is a list of.
 * It used to start a session instead, on the grounds that a template is really something you
 * save out of a workout you have just done — true, and it is still the other way one is made
 * (see `SessionSummaryScreen`), but it left the tab as the only list in the app with no way to
 * add to it, and a user who already knows the plan they intend to follow with nothing to press.
 * The description says the other way, so the user who would rather perform it first knows that
 * road exists.
 */
@Composable
private fun TemplatesEmptyState(onNewTemplate: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        glyph = stringResource(R.string.sessions_templates_glyph),
        title = stringResource(R.string.sessions_templates_empty_title),
        description = stringResource(R.string.sessions_templates_empty_description),
        actionLabel = stringResource(R.string.sessions_templates_empty_action),
        action = onNewTemplate,
        modifier = modifier,
    )
}

/** The overflow's square, and the glyph in it. See [TemplateMenu]. */
private val MENU_SIZE: Dp = 36.dp
private val MENU_GLYPH_SIZE: Dp = 18.dp

/* Stable keys for the four items, none of which is a row — the rows are inside [LIST_KEY]. */
private const val EMPTY_KEY = "empty"
private const val HEADER_KEY = "header"
private const val LIST_KEY = "templates"
private const val FOOTER_KEY = "footer"
