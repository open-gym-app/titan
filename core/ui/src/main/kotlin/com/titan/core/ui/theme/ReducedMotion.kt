package com.titan.core.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user has asked the system to stop animating.
 *
 * ### Why the animator duration scale, and not a "reduce motion" flag
 *
 * Android has no `prefers-reduced-motion`. What it has is
 * [Settings.Global.ANIMATOR_DURATION_SCALE], which the accessibility settings and the
 * developer options both write, and which the platform's own animations already honour. A
 * scale of zero is the user saying *do not animate*, in the one place the system offers to
 * say it, so that is what this reads.
 *
 * It is a global setting rather than a per-app one, which is the point: a user who has turned
 * animation off has done it once, for everything, and an app that made them find a second
 * switch in its own settings would have missed what they asked for.
 *
 * ### Read once, provided down
 *
 * The value reaches call sites through [LocalReducedMotion] rather than by each of them
 * touching `Settings.Global`, for three reasons. It is a content-resolver read, which is not
 * something to do per frame in a composable that animates. It makes the behaviour *testable*
 * — a preview or a test passes `TitanTheme(reducedMotion = true)` and asserts the static
 * rendering, with no ability to write a global setting from a unit test. And it means a
 * screenshot test renders deterministically by construction rather than by luck.
 *
 * Overriding it means passing that parameter, **not** wrapping [TitanTheme] in a
 * `CompositionLocalProvider`: the theme provides this local itself, so an outer provision is
 * shadowed for everything inside it.
 *
 * `compositionLocalOf` rather than the static variant used by the token sets: this one can
 * change while the app is running — the user can flip it in settings and come back — and the
 * coarser invalidation is what would be wrong here.
 *
 * Defaults to **false**, so a composable rendered outside [TitanTheme] animates. The
 * accessible default would be `true`, but a silent `true` is a flourish that never appears
 * and is never noticed to be missing; a preview that animates when it should not is visible
 * the moment somebody looks at it.
 */
val LocalReducedMotion = compositionLocalOf { false }

/**
 * The system's current answer — [TitanTheme]'s default for its `reducedMotion` parameter.
 *
 * Not for screens to call. A screen asks [LocalReducedMotion], which a test can override and
 * this cannot.
 */
@Composable
@ReadOnlyComposable
internal fun systemReducedMotion(): Boolean = LocalContext.current.animatorDurationScale() == 0f

/**
 * The scale, or `1f` when it cannot be read.
 *
 * Falling back to "animations on" rather than off: the setting is absent on a device that has
 * never had it written, which is the ordinary case and not a request for stillness.
 */
private fun Context.animatorDurationScale(): Float =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
