package com.opengym.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.opengym.app.ui.OpenGymApp
import com.opengym.core.ui.theme.OpenGymTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single activity. Every screen is a destination inside [OpenGymApp]'s navigation graph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenGymTheme {
                OpenGymApp()
            }
        }
    }
}
