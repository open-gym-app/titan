package com.titan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.app.ui.TitanApp
import com.titan.core.ui.theme.TitanTheme
import com.titan.core.ui.theme.isDark
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity. Every screen is a destination inside [TitanApp]'s navigation graph.
 *
 * It also does the one thing that cannot be done inside the composition: it applies the stored
 * theme to the *window*. The colour scheme is a composition concern and belongs to
 * `TitanTheme`, but the system bars are the window's, and they have to agree with what is
 * drawn under them or a dark app gets dark status-bar icons on a dark bar.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before `super.onCreate`, which is where the window is built: this is what swaps the
        // manifest's `Theme.Titan.Starting` for `Theme.Titan` underneath the splash.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The splash stays up until the stored preferences arrive — a disk read that takes a
        // frame or two. Nothing is composed until then either, so the alternative is not "a
        // faster first frame" but the window background in the *system's* theme, or a first
        // frame in the wrong theme, corrected while the user watches. See [MainUiState].
        splashScreen.setKeepOnScreenCondition { viewModel.uiState.value is MainUiState.Loading }

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            when (val state = uiState) {
                MainUiState.Loading -> Unit

                is MainUiState.Ready -> TitanTheme(
                    theme = state.preferences.theme,
                    unitSystem = state.preferences.unitSystem,
                ) {
                    SystemBars(isDark = state.preferences.theme.isDark())
                    TitanApp(startsOnOnboarding = state.startsOnOnboarding)
                }
            }
        }
    }

    /**
     * Keeps the system bars' icon colour in step with the rendered scheme.
     *
     * `enableEdgeToEdge` is a window call rather than a composable, so it is re-issued from a
     * [DisposableEffect] keyed on the resolved darkness: the theme can change while the
     * activity is alive — the user flips the setting two taps away, or the device crosses into
     * night mode with the preference on `SYSTEM` — and a value read once in `onCreate` would
     * leave dark icons on a dark bar until the next launch.
     *
     * Both styles are `SystemBarStyle.auto` with the same colour for the light and dark cases,
     * which is what asks for a *transparent* bar in both while stating which icon set belongs
     * over it via [detectDarkMode]. The lambda is the whole mechanism; it is why this does not
     * simply pass one style or the other.
     */
    @Composable
    private fun SystemBars(isDark: Boolean) {
        DisposableEffect(isDark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(
                    lightScrim = android.graphics.Color.TRANSPARENT,
                    darkScrim = android.graphics.Color.TRANSPARENT,
                ) { isDark },
                navigationBarStyle = SystemBarStyle.auto(
                    lightScrim = NAVIGATION_BAR_SCRIM_LIGHT,
                    darkScrim = NAVIGATION_BAR_SCRIM_DARK,
                ) { isDark },
            )
            onDispose {}
        }
    }

    private companion object {
        /*
         * The three-button navigation bar's scrim, which the platform draws behind the
         * buttons on API levels that cannot make it fully transparent. These are androidx's
         * own defaults, restated because naming a style means naming its scrims.
         */
        const val NAVIGATION_BAR_SCRIM_LIGHT = 0xE6FFFFFF.toInt()
        const val NAVIGATION_BAR_SCRIM_DARK = 0x801B1B1B.toInt()
    }
}
