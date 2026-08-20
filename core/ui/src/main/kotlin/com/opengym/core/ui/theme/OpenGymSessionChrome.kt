package com.opengym.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The colours the active workout's own chrome is drawn in, reached through
 * [OpenGymTheme.sessionChrome].
 *
 * ### Why the workout has a palette of its own
 *
 * The wireframes draw the active session under a solid near-black bar carrying the elapsed
 * time, the pause control and END — inverted against every other screen in the app, which is
 * paper with hairlines on it. That is not decoration. A workout is a *mode*: the bottom bar is
 * hidden, the back gesture does not leave, and the one surface that says so is this one. A
 * header that dissolved into the page would leave nothing saying the rules are different here.
 *
 * ### It stays dark in both schemes
 *
 * Which is the whole reason these are tokens rather than `inverseSurface`. Material's inverse
 * slots invert *with* the scheme — dark-on-light becomes light-on-dark — so a header built from
 * them would be near-black in light mode and near-white in dark, and the mode would look like a
 * different feature depending on the time of day. Here the dark scheme's header goes *deeper*
 * than its page rather than lighter, so the band still reads as a distinct surface without ever
 * stopping being the dark bar the design is built around.
 *
 * ### The accent is the dark scheme's accent in both
 *
 * The light scheme's accent (`#CC4226`) is tuned to be read on paper; on a near-black ground it
 * is muddy, and it is the reason the dark scheme has a hotter one at all. Since this surface is
 * near-black in both schemes, the accent that belongs on it is the same in both — the same hue,
 * tuned for the ground it sits on, which is exactly the judgement `Color.kt` already makes once.
 *
 * Every pair here is asserted by `ColorSchemeContrastTest` alongside the two schemes, so a
 * header token cannot be nudged into failing AA without failing the build.
 *
 * @property surface the bar itself.
 * @property onSurface what is written on it: the session's name and the running clock.
 * @property onSurfaceVariant the supporting line — the eyebrow over the name, the exercise
 *   count. Dimmer, and still AA against [surface].
 * @property fill the ground of a control *on* the bar, i.e. the pause button. Its glyph carries
 *   the contrast, so this is a shade rather than a boundary — see the note in the contrast test.
 * @property accent the primary action on the bar, and the completed part of the progress rail.
 * @property onAccent what is written on [accent].
 * @property track the unfilled part of the progress rail. Decorative: the rail says how far
 *   through the workout the user is at a glance, and the figure beside it says it in words.
 */
@Immutable
data class OpenGymSessionChrome(
    val surface: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val fill: Color,
    val accent: Color,
    val onAccent: Color,
    val track: Color,
)

/*
 * The two sets. They differ by less than any other pair of tokens in the app, on purpose — see
 * the note above — and what differs is which way the bar steps away from the page it sits on.
 */

internal val OpenGymLightSessionChrome = OpenGymSessionChrome(
    // The wireframes' `ink`, which in the light scheme is the text colour. The bar is that
    // colour turned into a surface.
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFFAFAF7),
    onSurfaceVariant = Color(0xFFA9A9A4),
    fill = Color(0xFF303030),
    accent = Color(0xFFFF6B3D),
    onAccent = Color(0xFF2B0D02),
    track = Color(0xFF3D3D3D),
)

internal val OpenGymDarkSessionChrome = OpenGymSessionChrome(
    // Below the dark scheme's own `background` (#101014) rather than above it. Raising it
    // would make the bar read as a card floating over the page; sinking it keeps it reading
    // as the thing the page is inside.
    surface = Color(0xFF08080A),
    onSurface = Color(0xFFECECEA),
    onSurfaceVariant = Color(0xFFB4B4B0),
    fill = Color(0xFF22222A),
    accent = Color(0xFFFF6B3D),
    onAccent = Color(0xFF2B0D02),
    track = Color(0xFF2C2C35),
)
