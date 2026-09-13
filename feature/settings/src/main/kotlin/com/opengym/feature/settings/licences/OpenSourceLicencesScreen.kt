package com.opengym.feature.settings.licences

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.settings.R
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/**
 * Every runtime dependency the app ships, and the licence it comes under (#208).
 *
 * The list itself is not hand-maintained: the AboutLibraries Gradle plugin, applied to `:app`,
 * walks the dependency graph at build time and writes it to `R.raw.aboutlibraries`. This screen
 * only renders it — the one thing it owns is which columns show and what a tap does, both set
 * below to match the acceptance criteria: name, version and licence name per row, and tapping a
 * row opens the full licence text in a dialog.
 *
 * ### Why the resource is read by name
 *
 * [produceLibraries] with no argument resolves `raw/aboutlibraries` by *name*, through
 * `Resources.getIdentifier`, rather than through a compile-time `R.raw` constant — this module
 * cannot see `:app`'s generated `R` class, and `:app` is the only module the plugin is applied
 * to. AGP 9's optimized resource shrinking cannot see that lookup either, which is what
 * `app/src/main/res/xml/keep_aboutlibraries.xml` is for.
 */
@Composable
fun OpenSourceLicencesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val libraries by produceLibraries()
    OpenSourceLicencesScreen(libraries = libraries, onBack = onBack, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenSourceLicencesScreen(
    libraries: Libs?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { OpenSourceLicencesTopBar(onBack = onBack) },
    ) { innerPadding ->
        when (libraries) {
            null -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            else -> LibrariesContainer(
                libraries = libraries,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                // A plain list — name, version, licence name: the acceptance criteria ask for
                // exactly those three columns. The author and description are both off; a
                // description doubles the height of every row for text nobody asked to read
                // here. Tapping a row opens the licence text in a dialog by default when
                // `onLibraryClick` is left null — nothing to set for that, but only for a
                // licence that *has* text: the container ignores a tap on one that does not.
                // The text comes from `config/aboutlibraries/licenses/` at build time; see the
                // `aboutLibraries` block in `app/build.gradle.kts`.
                showAuthor = false,
                showDescription = false,
                showVersion = true,
                showLicenseBadges = true,
            )
        }
    }
}

/** The screen's own bar. The shell hides its chrome outside the tabs — see `OpenGymApp`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenSourceLicencesTopBar(onBack: () -> Unit) {
    Column {
        TopAppBar(
            title = {
                Text(
                    text = stringResource(R.string.settings_licences),
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
}

// --- previews -------------------------------------------------------------------------

private val PREVIEW_LIBRARIES = Libs(
    libraries = persistentListOf(
        previewLibrary(
            uniqueId = "androidx.room:room-runtime",
            name = "Room",
            version = "2.8.4",
            licenseName = "Apache-2.0",
        ),
        previewLibrary(
            uniqueId = "androidx.compose.ui:ui",
            name = "Compose UI",
            version = "1.9.6",
            licenseName = "Apache-2.0",
        ),
        previewLibrary(
            uniqueId = "com.google.dagger:hilt-android",
            name = "Hilt",
            version = "2.60.1",
            licenseName = "Apache-2.0",
        ),
    ),
    licenses = persistentSetOf(),
)

private fun previewLibrary(
    uniqueId: String,
    name: String,
    version: String,
    licenseName: String,
) = Library(
    uniqueId = uniqueId,
    artifactVersion = version,
    name = name,
    description = null,
    website = null,
    developers = persistentListOf(),
    organization = null,
    scm = null,
    licenses = persistentSetOf(
        License(
            name = licenseName,
            url = null,
            year = null,
            spdxId = licenseName,
            licenseContent = null,
            hash = licenseName,
        ),
    ),
    funding = persistentSetOf(),
    tag = null,
)

@Preview(name = "Open source licences", showBackground = true, heightDp = 900)
@Composable
fun OpenSourceLicencesScreenPreview() {
    OpenGymTheme {
        OpenSourceLicencesScreen(libraries = PREVIEW_LIBRARIES, onBack = {})
    }
}

@Preview(name = "Open source licences - loading", showBackground = true, heightDp = 900)
@Composable
fun OpenSourceLicencesScreenLoadingPreview() {
    OpenGymTheme {
        OpenSourceLicencesScreen(libraries = null, onBack = {})
    }
}

/**
 * Every label at twice its size — the bar #40 holds every screen to, on the pattern Home set.
 */
@Preview(
    name = "Open source licences - twice the font",
    showBackground = true,
    heightDp = 900 * 2,
    fontScale = 2f,
)
@Composable
fun OpenSourceLicencesScreenLargeFontPreview() {
    OpenGymTheme {
        OpenSourceLicencesScreen(libraries = PREVIEW_LIBRARIES, onBack = {})
    }
}
