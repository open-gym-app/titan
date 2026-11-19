package com.titan.feature.titan.dock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.core.ui.component.titan.TitanDock
import com.titan.core.ui.component.titan.label
import com.titan.core.ui.component.titan.petSpeciesLabel
import com.titan.feature.titan.R
import com.titan.feature.titan.tab.status

/**
 * The dock as the scaffold places it: the active titan, or nothing at all when there is none.
 *
 * The scaffold decides where it may appear; this decides whether there is a titan to show.
 *
 * @param onOpen opens the Titan tab.
 */
@Composable
fun TitanDockHost(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TitanDockViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    (state as? TitanDockUiState.Shown)?.let { TitanDockContent(it, onOpen, modifier) }
}

/** The dock for [state], with its words resolved. */
@Composable
fun TitanDockContent(
    state: TitanDockUiState.Shown,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = state.name ?: petSpeciesLabel(state.speciesId)
    val status = stringResource(state.activity.status())
    val sentence = stringResource(R.string.titan_dock_description, name, status)
    val alert = state.alert?.let { stringResource(R.string.titan_dock_alert, it.label()) }
    TitanDock(
        header = stringResource(R.string.titan_header, name, state.level),
        status = status,
        activity = state.activity,
        vitals = state.vitals,
        alert = state.alert,
        head = state.head,
        face = state.face,
        contentDescription = listOfNotNull(sentence, alert, stringResource(R.string.titan_dock_open)).joinToString(" "),
        onClick = onOpen,
        modifier = modifier,
    )
}
