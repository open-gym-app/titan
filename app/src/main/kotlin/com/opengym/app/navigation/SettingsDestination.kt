package com.opengym.app.navigation

import kotlinx.serialization.Serializable

/**
 * Settings, reached from the gear in the top app bar.
 *
 * Declared in `:app` rather than in a feature, which breaks the rule that a destination
 * lives in the module owning its screen — because no module owns it yet. #97 creates
 * `:feature:settings`; when it does, this type and [com.opengym.app.ui.SettingsPlaceholderScreen]
 * move there together and `:app` goes back to only importing it.
 *
 * It sits outside the tab scaffold: settings is a place you leave to, not a tab.
 */
@Serializable
data object SettingsDestination
