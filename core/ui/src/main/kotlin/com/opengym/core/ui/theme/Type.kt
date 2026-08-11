package com.opengym.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * Two faces, and no bundled fonts.
 *
 * The wireframes name Inter Tight and JetBrains Mono. Neither is bundled: on Android the
 * platform sans is Roboto Flex, which is a close enough neighbour of Inter that shipping
 * ~400 KB of font binaries to close the gap is not a trade this app should make before it
 * has a single screen. `FontFamily.Default` and `FontFamily.Monospace` are the seam — if
 * the faces are ever bundled, they are set once, here, and every call site follows.
 *
 * What is *not* negotiable is the split. Prose is sans; every number is mono. A weight, a
 * rep count, a duration, a date, a set counter — all of it is monospace with tabular
 * figures, so a timer counting down does not make the layout twitch as digit widths change
 * and a column of weights lines up on the decimal without anybody laying out a table.
 */

private val Sans = FontFamily.Default
private val Mono = FontFamily.Monospace

/**
 * The tabular-figures opt-in.
 *
 * `FontFamily.Monospace` is already fixed-pitch, so this changes nothing for the *default*
 * mono face — it is here so the numeric roles stay correct if a proportional face is ever
 * swapped in above, which is exactly the change that would silently reintroduce jitter.
 */
private const val TABULAR_FIGURES = "tnum"

/**
 * The Material 3 roles, on the wireframes' scale.
 *
 * The scale is tighter than Material's defaults throughout — this is a dense, technical
 * layout with a lot of numbers per screen, not a reading surface. Titles are semi-bold with
 * slightly negative tracking; body is 13sp at a 1.45 line height.
 */
internal val OpenGymMaterialTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.02).em,
    ),
    displayMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.02).em,
    ),
    displaySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.015).em,
    ),
    headlineLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.015).em,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.01).em,
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).em,
    ),
    // The wireframes' `h1` — a screen title in the top app bar.
    titleLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.01).em,
    ),
    // `h2` — a card heading, a session name.
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.005).em,
    ),
    // `h3` — an exercise name in a row.
    titleSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    // `p` — the default for supporting prose.
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    // The wireframes' button label.
    labelLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.04.em,
    ),
)

/**
 * The two roles Material 3 does not have, reached through [OpenGymTheme.typography].
 *
 * Kept as a separate holder rather than folded into [Typography] so it is obvious at a call
 * site which of the two vocabularies a style comes from: `MaterialTheme.typography.titleMedium`
 * is a Material role, `OpenGymTheme.typography.eyebrow` is one of ours.
 */
@Immutable
data class OpenGymTypography(
    /**
     * The section header used throughout the wireframes — `THIS WEEK`, `RESUME · 00:23:14`,
     * `HISTORY`. Small, mono, widely tracked.
     *
     * **The style does not uppercase for you** — Compose has no text transform in [TextStyle].
     * Use `com.opengym.core.ui.component.Eyebrow`, which does, in the device's locale; reach
     * for the raw style only where the text is already uppercase for another reason.
     */
    val eyebrow: TextStyle,

    /** Hero figures: the running rest timer, a session's total volume on the summary. */
    val numericLarge: TextStyle,

    /** The default for any number in a row — a weight, a rep count, a duration. */
    val numeric: TextStyle,

    /** Numbers inside a chip or a dense table cell. */
    val numericSmall: TextStyle,
)

internal val OpenGymExtendedTypography = OpenGymTypography(
    eyebrow = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.12.em,
    ),
    numericLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.01).em,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    numeric = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
    numericSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
)
