package com.opengym.app.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.util.Consumer
import com.opengym.app.R
import com.opengym.app.navigation.OpenGymAppState
import com.opengym.app.navigation.OpenGymNavHost
import com.opengym.app.navigation.TopLevelDestination
import com.opengym.app.navigation.rememberOpenGymAppState
import com.opengym.feature.settings.navigation.SettingsDestination

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
 * its own: it is four questions with a progress rail over them.
 *
 * @param startsOnOnboarding where this launch opens — see `MainUiState.Ready`. Passed down
 *   rather than read here, because the answer is a *latched* one and a composable that read
 *   the live preference would rebuild the graph the moment onboarding recorded its own
 *   completion.
 */
@Composable
fun OpenGymApp(
    startsOnOnboarding: Boolean,
    modifier: Modifier = Modifier,
    appState: OpenGymAppState = rememberOpenGymAppState(),
) {
    val chrome = appState.chrome

    NewIntentDeepLinks(appState)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            chrome.currentTopLevelDestination
                ?.takeIf { chrome.shouldShowBottomBar }
                ?.let { destination ->
                    OpenGymTopBar(
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
                OpenGymBottomBar(
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
        OpenGymNavHost(
            appState = appState,
            startsOnOnboarding = startsOnOnboarding,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
    }
}

/**
 * Hands `NavController` the deep links that arrive after it was built.
 *
 * Navigation reads the launching intent exactly once, as the graph is created, which covers the
 * link that starts the app from cold. `MainActivity` is `singleTop` and this app is one
 * activity, so every *other* deep link — the notification tapped while the app is merely
 * backgrounded, with its window still alive behind the shade — is delivered to the running
 * instance through `onNewIntent`, where nothing was listening: the task came forward on
 * whichever screen the user left it on and the link went nowhere. That is the one failure a
 * deep link has no symptom for, since arriving at the wrong screen looks like an app that was
 * simply reopened.
 *
 * Registered from the composition rather than from `MainActivity` because the `NavController`
 * is the composition's, and an activity holding a reference to it would be the one place in
 * this app that navigation is driven from outside the graph that owns it.
 */
@Composable
private fun NewIntentDeepLinks(appState: OpenGymAppState) {
    val activity = LocalActivity.current as? ComponentActivity ?: return
    val navController = appState.navController

    DisposableEffect(activity, navController) {
        // `handleDeepLink` answers false for an intent that matches no destination — a share, a
        // launcher tap on a task already running — and does nothing, which is what should
        // happen to it.
        val listener = Consumer<Intent> { intent -> navController.handleDeepLink(intent) }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
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
private fun OpenGymTopBar(
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
