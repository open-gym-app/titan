package com.titan.feature.settings.healthconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.settings.R

/** Where the privacy policy is published. The same page the Play listing links to. */
const val PRIVACY_POLICY_URL = "https://open-gym-app.github.io/privacy"

/**
 * What Titan does with Health Connect data, for the page Health Connect itself opens.
 *
 * Health Connect requires every app that asks for its permissions to answer an intent with this
 * explanation: from the "learn more" link under its permission dialog, and on Android 14 and
 * later from the app's entry in Health Connect's settings. Without an activity that answers it,
 * Health Connect will not show the dialog at all. `:app` declares that activity and draws this.
 *
 * The same words as [HealthConnectRationaleDialog], on purpose: what the user is told before the
 * dialog and what Health Connect shows them from it must be one account of what is read and
 * written, and the link to the full policy covers everything the app does besides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthConnectPrivacyScreen(
    onBack: () -> Unit,
    onOpenPolicy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.health_connect_privacy_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.settings_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(TitanTheme.spacing.screen),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.section),
        ) {
            Text(
                text = stringResource(R.string.health_connect_rationale_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = { onOpenPolicy(PRIVACY_POLICY_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.health_connect_privacy_policy))
            }
        }
    }
}

@Preview(name = "Health Connect privacy", showBackground = true)
@Composable
fun HealthConnectPrivacyScreenPreview() {
    TitanTheme {
        HealthConnectPrivacyScreen(onBack = {}, onOpenPolicy = {})
    }
}
