package com.titan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.LocalUriHandler
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.healthconnect.HealthConnectPrivacyScreen

/**
 * What Titan does with Health Connect data, for Health Connect to open (#54).
 *
 * Health Connect will not show its permission dialog to an app that has no activity answering
 * for this, and opens it from two places — see the manifest, which declares it under both of the
 * names the platform has used. A separate activity rather than a route into `MainActivity`:
 * another app starts it, and `MainActivity`'s intent handling is deliberately narrow about what
 * an outside intent may do to the app behind it (#270). This one shows a page and finishes.
 *
 * In the system theme rather than the user's chosen one, because reading the preference store is
 * a coroutine and a frame of the wrong colours is better than a blank one for a page of text.
 */
class HealthConnectPrivacyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val uriHandler = LocalUriHandler.current
            TitanTheme {
                HealthConnectPrivacyScreen(
                    onBack = ::finish,
                    // Opens the browser, which has the network this app has none of.
                    onOpenPolicy = { url -> runCatching { uriHandler.openUri(url) } },
                )
            }
        }
    }
}
