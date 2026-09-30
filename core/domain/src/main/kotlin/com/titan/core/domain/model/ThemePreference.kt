package com.titan.core.domain.model

/**
 * Which colour scheme the app renders in.
 *
 * Three values rather than a boolean, because "follow the system" is a distinct choice
 * and not the absence of one: a user on [SYSTEM] whose device flips to dark at sunset
 * expects the app to follow, and storing that as "light, currently" would freeze it at
 * whatever the device happened to be when they last looked.
 */
enum class ThemePreference {

    LIGHT,

    DARK,

    /** Follow the device's own light/dark setting, whatever it is at render time. */
    SYSTEM,
}
