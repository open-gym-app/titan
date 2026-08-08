package com.opengym.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app theme: colour, typography, shape, and the two token sets Material 3 has no slot
 * for ([OpenGymSpacing] and [OpenGymTypography]).
 *
 * ## Dynamic colour is not supported, and that is a decision
 *
 * Material You would repaint `primary` from the user's wallpaper. This design cannot take
 * that: the accent is not decoration, it is the app's only signal. A personal record badge,
 * the set you are currently working, the running rest timer and the primary action are all
 * *the accent* and nothing else — that is what lets a glance between sets find the one thing
 * that matters. Wallpaper-derived colour makes that signal a different colour on every
 * device, and on a pastel wallpaper it stops being a signal at all. It would also throw away
 * the contrast work in [Color.kt]: the schemes here are asserted against WCAG AA pair by
 * pair, and a generated scheme is asserted against nothing.
 *
 * Revisit this only alongside a plan for what the accent-carrying elements do instead.
 *
 * @param darkTheme whether to use the dark scheme. Follows the system by default; the
 *   preference store (#90) will pass an explicit override once it exists.
 */
@Composable
fun OpenGymTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalOpenGymSpacing provides OpenGymSpacing(),
        LocalOpenGymTypography provides OpenGymExtendedTypography,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) OpenGymDarkColorScheme else OpenGymLightColorScheme,
            typography = OpenGymMaterialTypography,
            shapes = OpenGymShapes,
            content = content,
        )
    }
}

/**
 * The accessor for the tokens Material's own `MaterialTheme` object does not carry.
 *
 * Colour, the Material type roles and shape stay on `MaterialTheme` — there is no wrapper
 * for them, because a second way to reach the same value is a second thing to keep in step.
 * Reach for `OpenGymTheme.x` only for what is genuinely ours:
 *
 * ```kotlin
 * Text(
 *     text = "THIS WEEK",
 *     style = OpenGymTheme.typography.eyebrow,
 *     color = MaterialTheme.colorScheme.onSurfaceVariant,
 *     modifier = Modifier.padding(bottom = OpenGymTheme.spacing.md),
 * )
 * ```
 */
object OpenGymTheme {

    /** The spacing rhythm. See [OpenGymSpacing]. */
    val spacing: OpenGymSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenGymSpacing.current

    /** The eyebrow and numeric roles. See [OpenGymTypography]. */
    val typography: OpenGymTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenGymTypography.current
}

/*
 * `staticCompositionLocalOf`, not `compositionLocalOf`: neither set of tokens changes for
 * the life of the composition, so the cheaper local that recomposes everything below it on
 * a change is the right trade — the change never happens.
 *
 * Both default to the real tokens rather than throwing, so a composable rendered outside
 * `OpenGymTheme` — which is every `@Preview` somebody forgets to wrap — comes out with the
 * right spacing instead of an error. It will still have Material's default colours, which
 * is the visible signal that the wrapper is missing.
 */
internal val LocalOpenGymSpacing = staticCompositionLocalOf { OpenGymSpacing() }
internal val LocalOpenGymTypography = staticCompositionLocalOf { OpenGymExtendedTypography }
