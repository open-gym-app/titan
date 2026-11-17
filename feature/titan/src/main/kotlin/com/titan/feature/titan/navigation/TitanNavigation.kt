package com.titan.feature.titan.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navDeepLink
import com.titan.core.domain.navigation.DeepLinks
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
 * @param onOpenSpar the Spar option, which opens the spar screen rather than starting a bout (#299).
 */
fun NavGraphBuilder.titanGraph(
    onOpenInventory: () -> Unit,
    onOpenSpar: () -> Unit,
) {
    navigation<TitanGraphDestination>(startDestination = TitanTabDestination) {
        composable<TitanTabDestination>(
            deepLinks = listOf(navDeepLink<TitanTabDestination>(basePath = DeepLinks.TITANS)),
        ) {
            TitanTabRoute(onOpenInventory = onOpenInventory, onOpenSpar = onOpenSpar)
        }
    }
}
