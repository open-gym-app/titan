package com.opengym.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What each kind of move animates as.
 *
 * Against [motionBetween] rather than the four `AnimatedContentTransitionScope` extensions,
 * because the rule under test is the classification — the extensions are a `when` over its
 * result. Taking [ShellChrome] rather than `NavDestination` is what makes it testable without
 * a graph: the shell's reading of a destination is a value, and this is a function of two of
 * them.
 */
class NavMotionTest {

    @Test
    fun `switching between two tabs is lateral`() {
        assertEquals(
            NavMotion.LATERAL,
            motionBetween(tab(TopLevelDestination.HOME), tab(TopLevelDestination.SESSIONS)),
        )
    }

    @Test
    fun `pushing a screen inside a tab is forward`() {
        // Both ends are the Sessions tab: the list and the detail below it read identically
        // to the shell, which is exactly what says this is movement within one place.
        assertEquals(
            NavMotion.FORWARD,
            motionBetween(tab(TopLevelDestination.SESSIONS), tab(TopLevelDestination.SESSIONS)),
        )
    }

    @Test
    fun `entering the session flow is modal`() {
        assertEquals(NavMotion.MODAL, motionBetween(tab(TopLevelDestination.HOME), sessionFlow()))
    }

    @Test
    fun `leaving the session flow is modal`() {
        assertEquals(NavMotion.MODAL, motionBetween(sessionFlow(), tab(TopLevelDestination.HOME)))
    }

    @Test
    fun `moving between two steps of the session flow is forward`() {
        // The flow is a mode to enter and leave, not a mode to move around inside: setup to
        // active is a step down a sequence and should look like one.
        assertEquals(NavMotion.FORWARD, motionBetween(sessionFlow(), sessionFlow()))
    }

    @Test
    fun `opening settings from a tab is forward, not lateral`() {
        // Settings is outside the tabs but it is not a peer of them — it is pushed on top of
        // wherever the user was, and a fade would lose that it can be backed out of.
        assertEquals(NavMotion.FORWARD, motionBetween(tab(TopLevelDestination.HOME), outsideTabs()))
    }

    @Test
    fun `negative - a tab and itself is not lateral`() {
        // The guard that keeps a push inside a tab from fading: "different destinations" is
        // not the question, "different tabs" is.
        assertEquals(
            NavMotion.FORWARD,
            motionBetween(tab(TopLevelDestination.STATS), tab(TopLevelDestination.STATS)),
        )
    }

    @Test
    fun `negative - two destinations outside the tabs are not lateral`() {
        // Neither end is a tab, so there is no lateral relationship to read into it.
        assertEquals(NavMotion.FORWARD, motionBetween(outsideTabs(), outsideTabs()))
    }

    private fun tab(destination: TopLevelDestination) = ShellChrome(
        currentTopLevelDestination = destination,
        isInSessionFlow = false,
    )

    private fun sessionFlow() = ShellChrome(
        currentTopLevelDestination = null,
        isInSessionFlow = true,
    )

    /** Settings, onboarding — outside the tabs without being the session mode. */
    private fun outsideTabs() = ShellChrome(
        currentTopLevelDestination = null,
        isInSessionFlow = false,
    )
}
