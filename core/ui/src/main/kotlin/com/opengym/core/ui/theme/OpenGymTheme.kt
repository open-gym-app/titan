package com.opengym.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.UnitSystem

/**
 * The app theme: colour, typography, shape, and the token sets Material 3 has no slot for
 * ([OpenGymSpacing] and [OpenGymTypography]).
 *
 * It also carries the two *stored* preferences that change how every screen renders — the
 * colour scheme and the unit system. Both arrive as the domain enums rather than as a
 * `Boolean` and a formatter argument, and both are provided down the tree, because they are
 * the same kind of thing: a setting the user changes in one place that must take effect
 * everywhere, immediately, without a screen having to subscribe to anything. `:app` collects
 * `UserPreferencesRepository` once at the root and passes them here; a preference write
 * recomposes the app, and #M4-23's "no restart" criterion is that sentence.
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
 * @param theme which colour scheme to render in. [ThemePreference.SYSTEM] follows the device,
 *   live — see [ThemePreference.isDark].
 * @param unitSystem the units weights and distances are *displayed* in; nothing stored is
 *   affected. Reached from a call site through [LocalUnitSystem].
 */
@Composable
fun OpenGymTheme(
    theme: ThemePreference = ThemePreference.SYSTEM,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    content: @Composable () -> Unit,
) {
    val dark = theme.isDark()

    CompositionLocalProvider(
        LocalOpenGymSpacing provides OpenGymSpacing(),
        LocalOpenGymTypography provides OpenGymExtendedTypography,
        LocalOpenGymPlateColors provides
            if (dark) OpenGymDarkPlateColors else OpenGymLightPlateColors,
        LocalOpenGymSessionChrome provides
            if (dark) OpenGymDarkSessionChrome else OpenGymLightSessionChrome,
        LocalUnitSystem provides unitSystem,
    ) {
        MaterialTheme(
            colorScheme = if (dark) OpenGymDarkColorScheme else OpenGymLightColorScheme,
            typography = OpenGymMaterialTypography,
            shapes = OpenGymShapes,
            content = content,
        )
    }
}

/**
 * Whether this preference means "dark", *right now*.
 *
 * [ThemePreference.SYSTEM] is answered by asking the device on every composition rather than
 * by resolving it once at startup, which is the whole reason the preference stores three
 * values instead of a boolean: a user on `SYSTEM` whose phone flips to dark at sunset expects
 * the app to follow without being reopened.
 *
 * Public because `:app` needs the same answer outside the theme — the system bars' icon
 * colour is set on the window, not in the composition, and it has to agree with what is drawn
 * under it.
 */
@Composable
@ReadOnlyComposable
fun ThemePreference.isDark(): Boolean = when (this) {
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
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

    /** The ramp a loaded barbell is drawn in. See [OpenGymPlateColors]. */
    val plateColors: OpenGymPlateColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenGymPlateColors.current

    /** The dark bar the active workout runs under. See [OpenGymSessionChrome]. */
    val sessionChrome: OpenGymSessionChrome
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenGymSessionChrome.current
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

/*
 * The plate ramp is the exception to the paragraph above: unlike spacing and type, it *is*
 * different in the two schemes, so it is provided rather than defaulted-and-forgotten and
 * `compositionLocalOf` would be the safer choice. It stays static anyway, because the scheme
 * cannot change without `OpenGymTheme` recomposing and taking everything under it along.
 * Defaulting to the light ramp keeps an unwrapped preview drawing plates rather than throwing,
 * which is the same trade the two above make.
 */
internal val LocalOpenGymPlateColors = staticCompositionLocalOf { OpenGymLightPlateColors }

/*
 * The workout's chrome, on the same terms as the plate ramp above: provided per scheme,
 * static because the scheme cannot change without this composable recomposing, and defaulted
 * to the light set so an unwrapped preview still draws the bar.
 */
internal val LocalOpenGymSessionChrome = staticCompositionLocalOf { OpenGymLightSessionChrome }

/**
 * The units every number the user reads is rendered in.
 *
 * A composition local rather than a parameter threaded through the screens, because the
 * alternative is every composable that shows a weight taking a `UnitSystem` it does nothing
 * with but pass on — and the first one that forgets renders kilograms on an imperial user's
 * screen with nothing to indicate it. The formatters read it themselves; see
 * `com.opengym.core.ui.format`.
 *
 * `compositionLocalOf`, unlike the two above, because this one *does* change while the app
 * is running: flipping the setting has to repaint every weight on screen, and the static
 * variant's coarser invalidation is exactly the behaviour that would be wrong here.
 *
 * Defaulting to [UnitSystem.METRIC] means a preview or a test that skips [OpenGymTheme]
 * renders in metric rather than crashing, matching [com.opengym.core.domain.model.UserPreferences]'
 * own default.
 */
val LocalUnitSystem = compositionLocalOf { UnitSystem.METRIC }
