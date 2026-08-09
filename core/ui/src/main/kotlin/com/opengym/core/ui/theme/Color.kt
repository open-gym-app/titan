package com.opengym.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * The palette, and the map from the wireframes' token names onto Material 3's slots.
 *
 * The wireframes are drawn around a *single* accent — a burnt orange — on a neutral,
 * ruled surface. Everything that matters (a PR badge, the active set row, the running
 * rest timer, the primary action) is that one colour, and everything else is a neutral.
 * That is the whole idea: if a second hue starts competing, the accent stops meaning
 * "this is the thing" and starts meaning "this is decorated". Secondary and tertiary are
 * therefore warm and cool *neutrals* rather than a second and third hue — see below.
 *
 * | Wireframe token | Material 3 slot                | Light     | Dark      |
 * | --------------- | ------------------------------ | --------- | --------- |
 * | `accent`        | `primary`                      | `#CC4226` | `#FF6B3D` |
 * | `accent-soft`   | `primaryContainer`             | `#F6DFD6` | `#4A1E0E` |
 * | `paper`         | `background` / `surface`       | `#FAFAF7` | `#101014` |
 * | `card`          | `surfaceContainerLow`          | `#FFFFFF` | `#191920` |
 * | `fill`          | `surfaceContainer`             | `#EDEDEA` | `#22222A` |
 * | `fill-deep`     | `surfaceContainerHigh`         | `#D8D8D4` | `#2C2C35` |
 * | `ink`           | `onSurface`                    | `#1A1A1A` | `#ECECEA` |
 * | `ink-soft`      | `onSurfaceVariant`             | `#4F4F4C` | `#B4B4B0` |
 * | `ink-faint`     | `outline`                      | `#6E6E6A` | `#8A8A84` |
 * | `rule`          | `outlineVariant`               | `#C8C8C8` | `#3A3A40` |
 * | `rule-soft`     | `outlineVariant` at 50% alpha  | —         | —         |
 *
 * Three deliberate departures from the wireframes, all of them forced by contrast:
 *
 * - **The accent is a step darker in light** (`#CC4226`, not the wireframes' `#D24727`).
 *   The wireframe value is 4.50:1 against paper and 4.497:1 under white text — it misses
 *   AA by a rounding error in exactly the two places it is used most, as a filled button
 *   and as accent text. `#CC4226` is the nearest value that clears both.
 * - **A filled accent button carries dark text in dark mode.** The wireframes put white on
 *   `#FF6B3D`, which is 2.83:1. An accent bright enough to read as *hot* on a near-black
 *   surface is by construction too bright to sit under white text; `onPrimary` is a very
 *   dark brown instead.
 * - **`ink-faint` is darker than drawn** (`#6E6E6A` rather than `#8A8A8A`). It maps to
 *   `outline`, which draws the boundary of controls — outlined buttons, unselected chips —
 *   and those need 3:1. The hairline *rules* keep the drawn weight via `outlineVariant`,
 *   which WCAG exempts as decorative.
 *
 * Every pair is asserted, not asserted-once-and-hoped: `ColorSchemeContrastTest` fails the
 * build if any text pair drops below 4.5:1 or any control boundary below 3:1.
 */

// ─── Light ──────────────────────────────────────────────────────────────────────────

private val AccentLight = Color(0xFFCC4226)
private val AccentSoftLight = Color(0xFFF6DFD6)
private val PaperLight = Color(0xFFFAFAF7)
private val CardLight = Color(0xFFFFFFFF)
private val FillLight = Color(0xFFEDEDEA)
private val FillDeepLight = Color(0xFFD8D8D4)
private val InkLight = Color(0xFF1A1A1A)
private val InkSoftLight = Color(0xFF4F4F4C)
private val InkFaintLight = Color(0xFF6E6E6A)
private val RuleLight = Color(0xFFC8C8C8)

internal val OpenGymLightColorScheme: ColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = AccentSoftLight,
    onPrimaryContainer = Color(0xFF4A1408),
    inversePrimary = Color(0xFFFF8A5E),

    // Warm taupe rather than a second hue: it is the accent's own hue drained of chroma,
    // so a secondary surface still belongs to the same family without competing with it.
    secondary = Color(0xFF6F5A52),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFE5E0),
    onSecondaryContainer = Color(0xFF2A1C16),

    // Cool stone, and deliberately not a third hue — see the note at the top of the file.
    tertiary = Color(0xFF5C5C57),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE6E6E1),
    onTertiaryContainer = Color(0xFF1B1B18),

    background = PaperLight,
    onBackground = InkLight,
    surface = PaperLight,
    onSurface = InkLight,
    surfaceVariant = FillLight,
    onSurfaceVariant = InkSoftLight,
    surfaceTint = AccentLight,

    // `surfaceContainerLow` is the card level in both schemes. `Lowest` matches it here
    // because in a paper-white design there is nothing *below* a card to distinguish.
    surfaceContainerLowest = CardLight,
    surfaceContainerLow = CardLight,
    surfaceContainer = FillLight,
    surfaceContainerHigh = Color(0xFFE6E6E2),
    surfaceContainerHighest = FillDeepLight,

    inverseSurface = Color(0xFF2A2A28),
    inverseOnSurface = Color(0xFFF2F2EE),

    // A crimson, not the obvious brick red: destructive actions sit next to accent-coloured
    // ones ("Discard" beside "Resume"), and two reds a hue apart is not a distinction
    // anybody makes at a glance in a gym.
    error = Color(0xFFA4123A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE1E7),
    onErrorContainer = Color(0xFF3F0417),

    outline = InkFaintLight,
    outlineVariant = RuleLight,
    scrim = Color(0xFF000000),
)

// ─── Dark ───────────────────────────────────────────────────────────────────────────

/*
 * Not an inversion of the light scheme. The light scheme is paper — warm, near-white,
 * hairlines you have to look for. The dark scheme is an instrument panel: a blue-black
 * ground the orange reads as *heat* against, with the surface levels stepping up in even
 * increments so a card, a fill and a raised fill stay distinguishable on a phone held at
 * arm's length under gym lighting. The blue cast is what does that work — a neutral grey
 * ground makes the same orange look muddy.
 */

private val AccentDark = Color(0xFFFF6B3D)
private val PaperDark = Color(0xFF101014)
private val CardDark = Color(0xFF191920)
private val FillDark = Color(0xFF22222A)
private val FillDeepDark = Color(0xFF2C2C35)
private val InkDark = Color(0xFFECECEA)
private val InkSoftDark = Color(0xFFB4B4B0)
private val InkFaintDark = Color(0xFF8A8A84)
private val RuleDark = Color(0xFF3A3A40)

internal val OpenGymDarkColorScheme: ColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF2B0D02),
    primaryContainer = Color(0xFF4A1E0E),
    onPrimaryContainer = Color(0xFFFFD6C4),
    inversePrimary = Color(0xFFB03A1C),

    secondary = Color(0xFFD6BFB5),
    onSecondary = Color(0xFF3A2A22),
    secondaryContainer = Color(0xFF503C33),
    onSecondaryContainer = Color(0xFFF3E1D9),

    tertiary = Color(0xFFC9C9C2),
    onTertiary = Color(0xFF2E2E2A),
    tertiaryContainer = Color(0xFF45453F),
    onTertiaryContainer = Color(0xFFE8E8E1),

    background = PaperDark,
    onBackground = InkDark,
    surface = PaperDark,
    onSurface = InkDark,
    surfaceVariant = FillDark,
    onSurfaceVariant = InkSoftDark,
    surfaceTint = AccentDark,

    surfaceContainerLowest = Color(0xFF0A0A0C),
    surfaceContainerLow = CardDark,
    surfaceContainer = FillDark,
    surfaceContainerHigh = FillDeepDark,
    surfaceContainerHighest = Color(0xFF37373F),

    inverseSurface = InkDark,
    inverseOnSurface = Color(0xFF1A1A1A),

    error = Color(0xFFFF9EB3),
    onError = Color(0xFF4E0018),
    errorContainer = Color(0xFF74102F),
    onErrorContainer = Color(0xFFFFDCE3),

    outline = InkFaintDark,
    outlineVariant = RuleDark,
    scrim = Color(0xFF000000),
)
