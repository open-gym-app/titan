package com.opengym.feature.progression.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.achievements.component.AchievementGrid

@Composable
fun AchievementsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AchievementsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AchievementsScreen(state = state, onBack = onBack, modifier = modifier)
}

/**
 * Every milestone in the catalogue, held and not.
 *
 * ### Its own screen, because an achievement is not a quest
 *
 * These used to be a grid at the bottom of the quest board, and the adjacency made both harder to
 * read: a rolling weekly target with a deadline sat beside a permanent one-shot unlock, drawn the
 * same way, under one heading. An achievement has no deadline, no partial progress and nothing to
 * act on today — which is precisely why it belongs one tap away rather than under the board a
 * lifter opens to see what is left this week. The quest log keeps the count and a way through.
 *
 * ### A lead line rather than an empty state
 *
 * There is no zero state. A user who holds none sees six locked cells and a `0 / 6`, which is a
 * correct and rather more inviting screen than a message saying they have earned nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AchievementsScreen(
    state: AchievementsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // Outside the tab scaffold, like every other progression drill-down.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.achievements_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.progression_detail_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = OpenGymTheme.spacing.screen,
                    vertical = OpenGymTheme.spacing.md,
                ),
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
        ) {
            SectionHeading(
                text = stringResource(R.string.achievements_heading),
                trailing = {
                    Text(
                        text = stringResource(
                            R.string.achievements_count,
                            state.unlockedCount,
                            state.entries.size,
                        ),
                        style = OpenGymTheme.typography.numericSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )

            Text(
                text = stringResource(R.string.achievements_caption),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            AchievementGrid(entries = state.entries)
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Achievements", showBackground = true, heightDp = 700)
@Composable
fun AchievementsScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        AchievementsScreen(state = populatedAchievements(), onBack = {})
    }
}

@Preview(name = "Achievements - dark", showBackground = true, heightDp = 700)
@Composable
fun AchievementsScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        AchievementsScreen(state = populatedAchievements(), onBack = {})
    }
}

@Preview(name = "Achievements - none held", showBackground = true, heightDp = 700)
@Composable
fun AchievementsScreenEmptyPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        AchievementsScreen(state = emptyAchievements(), onBack = {})
    }
}
