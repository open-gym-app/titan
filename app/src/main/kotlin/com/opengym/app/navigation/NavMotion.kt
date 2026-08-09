package com.opengym.app.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry

/**
 * What a move between two destinations *means*, which is what decides how it animates.
 *
 * The shell has three kinds of movement and they should not look alike, because they are not
 * alike. Left to the `NavHost` defaults they all get the same cross-fade-and-scale, which is
 * what makes the shell feel like it is melting rather than moving.
 */
internal enum class NavMotion {
    /** A sideways move between peers: one tab to another. Nothing is deeper than before. */
    LATERAL,

    /** A step down or back up a hierarchy: a list to its detail, a flow's step to the next. */
    FORWARD,

    /** Into or out of a mode that owns the whole screen: the session flow. */
    MODAL,
}

/**
 * Classify a move from the chrome each end of it implies.
 *
 * Deliberately derived from [ShellChrome] rather than from a list of routes: the shell
 * already decides what a destination *is* — which tab it belongs to, whether it is the
 * session mode — and a second, independent answer to that question is a second thing to keep
 * in step. Add a destination to a tab's graph and it animates correctly here for free.
 *
 * The order of the branches is the meaning:
 *
 * - crossing the session flow's boundary is a mode change whichever tab it happens from, so
 *   it is decided first and wins over everything else;
 * - two *different* tabs is the lateral case. Both ends must actually be tabs — moving from
 *   a tab to Settings is not lateral, it is a push into somewhere that is not a tab at all;
 * - everything else is movement within one place: a tab to a screen inside it, one step of
 *   the session flow to the next, a tab out to Settings.
 */
internal fun motionBetween(from: ShellChrome, to: ShellChrome): NavMotion = when {
    from.isInSessionFlow != to.isInSessionFlow -> NavMotion.MODAL

    from.currentTopLevelDestination != to.currentTopLevelDestination &&
        from.currentTopLevelDestination != null &&
        to.currentTopLevelDestination != null -> NavMotion.LATERAL

    else -> NavMotion.FORWARD
}

/**
 * A tab switch is a jump, not a journey — there is no direction to show, so a fast fade is
 * the whole animation. Long enough not to flicker, short enough that the bar feels like it
 * is switching a view rather than playing something.
 */
private const val LATERAL_MS = 150

/** A push. Material's shared-axis duration; slow enough to read as depth. */
private const val FORWARD_MS = 300

/** A mode change. Slightly longer, because it is the largest change the shell can make. */
private const val MODAL_MS = 350

/**
 * How far the screen being left behind travels during a push, as a fraction of the width.
 *
 * The two screens moving at different speeds is what reads as one sliding *over* the other;
 * moving both by a full width reads as a filmstrip, where nothing is on top of anything.
 */
private const val PARALLAX_DIVISOR = 4

private fun AnimatedContentTransitionScope<NavBackStackEntry>.motion(): NavMotion =
    motionBetween(
        from = ShellChrome.of(initialState.destination),
        to = ShellChrome.of(targetState.destination),
    )

/** Arriving somewhere new. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    when (motion()) {
        NavMotion.LATERAL -> fadeIn(tween(LATERAL_MS))
        NavMotion.FORWARD -> slideInHorizontally(tween(FORWARD_MS)) { width -> width } +
            fadeIn(tween(FORWARD_MS))
        NavMotion.MODAL -> slideInVertically(tween(MODAL_MS)) { height -> height }
    }

/** Leaving somewhere that stays on the back stack, to be returned to. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    when (motion()) {
        NavMotion.LATERAL -> fadeOut(tween(LATERAL_MS))
        NavMotion.FORWARD ->
            slideOutHorizontally(tween(FORWARD_MS)) { width -> -width / PARALLAX_DIVISOR } +
                fadeOut(tween(FORWARD_MS))
        // The mode slides up over the tab rather than the tab getting out of its way: what is
        // underneath is where the user is going back to, and it should look like it is still
        // there.
        NavMotion.MODAL -> fadeOut(tween(MODAL_MS))
    }

/** Coming back to somewhere that was left on the back stack. The mirror of [exit]. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    when (motion()) {
        NavMotion.LATERAL -> fadeIn(tween(LATERAL_MS))
        NavMotion.FORWARD ->
            slideInHorizontally(tween(FORWARD_MS)) { width -> -width / PARALLAX_DIVISOR } +
                fadeIn(tween(FORWARD_MS))
        NavMotion.MODAL -> fadeIn(tween(MODAL_MS))
    }

/** Being popped off the back stack for good. The mirror of [enter]. */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    when (motion()) {
        NavMotion.LATERAL -> fadeOut(tween(LATERAL_MS))
        NavMotion.FORWARD -> slideOutHorizontally(tween(FORWARD_MS)) { width -> width } +
            fadeOut(tween(FORWARD_MS))
        NavMotion.MODAL -> slideOutVertically(tween(MODAL_MS)) { height -> height }
    }
