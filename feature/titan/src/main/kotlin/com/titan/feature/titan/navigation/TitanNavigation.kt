package com.titan.feature.titan.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.titan.feature.titan.tab.TitanTabRoute
import kotlinx.serialization.Serializable

@Serializable
data object TitanGraphDestination

@Serializable
data object TitanTabDestination

/**
 * The Titan tab, as a top-level graph.
 *
 * @param onOpenInventory the supplies' Inventory link (#298).
 */
fun NavGraphBuilder.titanGraph(onOpenInventory: () -> Unit) {
    navigation<TitanGraphDestination>(startDestination = TitanTabDestination) {
        // No `deepLinks` here: `DeepLinks.TITANS` names a tab, and the app opens it the way a tap
        // on the tab does (`TitanAppState.openDeepLink`). Registered as a deep link, it would be
        // navigated into, and the tab bar's save-and-restore would then send Home back here.
        composable<TitanTabDestination> {
            TitanTabRoute(onOpenInventory = onOpenInventory)
        }
    }
}
