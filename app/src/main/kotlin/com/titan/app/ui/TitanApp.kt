package com.titan.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.titan.app.R
import com.titan.app.navigation.TitanAppState
import com.titan.app.navigation.TitanNavHost
import com.titan.app.navigation.TopLevelDestination
import com.titan.app.navigation.rememberTitanAppState
import com.titan.feature.settings.navigation.SettingsDestination

/**
 * The app shell: the chrome around the navigation graph.
 *
 * The chrome is driven entirely by *where the user is*, which is the only state this
 * composable has. Both bars are conditional on the same question — is this a destination
 * inside the tab scaffold? — so there is one rule to reason about rather than a bar that
 * hides and a bar that does not.
 *
 * Screens outside the tabs draw their own top bar. A workout's chrome is not a title and a
 * settings gear; it is elapsed time, a finish action and a discard action, and it belongs
 * to the screen that owns those (#25, #26, #27). Onboarding draws neither bar and no bar of
 * its own: it is five steps with a progress rail over them.
 *
 * @param startsOnOnboarding where this launch opens — see `MainUiState.Ready`. Passed down
 *   rather than read here, because the answer is a *latched* one and a composable that read
 *   the live preference would rebuild the graph the moment onboarding recorded its own
 *   completion.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TitanApp(
    startsOnOnboarding: Boolean,
    modifier: Modifier = Modifier,
    appState: TitanAppState = rememberTitanAppState(),
) {
    val chrome = appState.chrome

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            // Publishes every `Modifier.testTag` in the tree as an accessibility *resource id*,
            // which is the only handle UiAutomator has on a Compose node. Set once, at the
            // root, because it is inherited by everything below — see `TitanTestTags` for
            // the short list of nodes that carry a tag and why matching on visible text was
            // not an option for an app that ships in four languages.
            //
            // It changes nothing a user can see and nothing TalkBack reads: the resource id is
            // a field on the node, not its description.
            .semantics { testTagsAsResourceId = true },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            chrome.currentTopLevelDestination
                ?.takeIf { chrome.shouldShowBottomBar }
                ?.let { destination ->
                    TitanTopBar(
                        destination = destination,
                        // `launchSingleTop` so a double tap on the gear does not stack a
                        // second, identical Settings screen behind the first — which reads
                        // as a back press that did nothing.
                        onSettingsClick = {
                            appState.navController.navigate(SettingsDestination) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
        },
        bottomBar = {
            if (chrome.shouldShowBottomBar) {
                TitanBottomBar(
                    destinations = TopLevelDestination.entries,
                    currentDestination = chrome.currentTopLevelDestination,
                    onNavigate = appState::navigateToTopLevelDestination,
                )
            }
        },
    ) { innerPadding ->
        // Padded *and* consumed. The padding reserves the space; consuming it tells everything
        // below that the system bars have been dealt with, which is what stops a destination
        // outside the tabs from insetting itself a second time. Without it, a screen that pads
        // itself for the navigation bar — the picker's confirm bar, the detail screen's
        // add-to-session bar — sits a whole navigation bar too high, because the Scaffold has
        // already spent that space on a destination with no bottom bar of its own.
        TitanNavHost(
            appState = appState,
            startsOnOnboarding = startsOnOnboarding,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
    }
}

/**
 * The tab scaffold's top bar: the tab's name, and the way into settings.
 *
 * Settings lives here rather than in the bottom bar because of how often it is used. A tab
 * is for somewhere the user goes during a workout; settings is somewhere they go twice —
 * once to set their units, once to change the rest-timer default — and a permanent fifth of
 * the bar is too much room to give that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitanTopBar(
    destination: TopLevelDestination,
    onSettingsClick: () -> Unit,
) {
    Column {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(destination.labelRes),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            actions = {
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        // The wireframes rule off every bar rather than floating it on elevation. A hairline
        // is legible in a gym; a 1dp shadow is not.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
