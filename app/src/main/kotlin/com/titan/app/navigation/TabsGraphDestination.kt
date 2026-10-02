package com.titan.app.navigation

import kotlinx.serialization.Serializable

/**
 * The tab scaffold: everything the bottom bar can reach.
 *
 * A graph rather than three sibling destinations for two reasons. It makes "is the user
 * inside the tabs?" one question the shell can ask of any destination, and it gives per-tab
 * back-stack saving something to pop up to — `popUpTo(graph.findStartDestination())` needs a
 * start destination that belongs to the tabs and not to the app as a whole.
 */
@Serializable
data object TabsGraphDestination
