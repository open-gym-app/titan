// Named for the convention every feature's navigation file follows (`XNavigation.kt`) rather
// than for the one destination it happens to declare.
@file:Suppress("MatchingDeclarationName")

package com.titan.feature.onboarding.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.titan.feature.onboarding.OnboardingRoute
import kotlinx.serialization.Serializable

/**
 * The first-run flow, and where the app opens on a fresh install.
 *
 * **One flat destination for all four steps**, not a nested graph with one per step. The step
 * is state inside the screen (see `OnboardingScreen`), which is what makes "completing
 * onboarding leaves nothing of it on the back stack" a single `popUpTo` in `:app` rather than
 * a property that has to survive every future step being added.
 *
 * It sits *outside* the tab scaffold, like the session flow and settings, so neither bar is
 * drawn over it: onboarding is a mode with one way forward, and a bottom bar offering a jump
 * to Stats before the user has logged a set is an invitation to abandon it.
 */
@Serializable
data object OnboardingDestination

/**
 * @param onFinished onboarding is over *and recorded*. `:app` turns this into the move to Home
 *   that drops this destination — the flow is finished, and a back gesture from Home that
 *   returned to it would be asking the four questions again.
 */
fun NavGraphBuilder.onboardingGraph(onFinished: () -> Unit) {
    composable<OnboardingDestination> {
        OnboardingRoute(onFinished = onFinished)
    }
}
