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
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.achievements.component.LatestMedalCard
import com.opengym.feature.progression.achievements.component.MedalShelf
import com.opengym.feature.progression.achievements.component.NoMedalsYetCard

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
 * Every milestone in the catalogue as a medal, with the newest one struck again on the way in.
 *
 * ### Its own screen, because an achievement is not a quest
 *
 * These used to be a grid at the bottom of the quest board, and the adjacency made both harder to
 * read: a rolling weekly target with a deadline sat beside a permanent one-shot unlock, drawn the
 * same way, under one heading. An achievement has no deadline, no partial progress and nothing to
 * act on today — which is precisely why it belongs one tap away rather than under the board a
 * lifter opens to see what is left this week. The quest log keeps the count and a way through.
 *
 * ### A trophy case, not a checklist
 *
 * The first version of this screen drew each milestone as a bordered cell carrying its name and,
 * for the ones not held, the word `Locked`. It was accurate and it read as a list of things the
 * user had failed to do — six boxes, four of them saying no. What is actually being shown is a
 * *collection*: permanent objects, ranked, that arrive one at a time and never leave. So they are
 * drawn as objects — see `AchievementMedal` — the newest one leads the screen at a size worth
 * looking at, and earning one is an event with an animation rather than a cell changing colour.
 *
 * ### A hero rather than an empty state
 *
 * There is still no zero state. A user who holds none gets a blank disc where the newest medal
 * goes, one line saying what strikes the first, and the whole catalogue below it at `0 / 6` — a
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
        // Nothing at all until the read lands, the way the summary and the dashboard wait. The
        // catalogue is a compile-time constant but *what is held* is not, and drawing early
        // tells a user with five medals that they have none, at `0 / 0`, for the frame the query
        // takes. Worse than the flicker: the hero is keyed on the milestone, so the correction
        // strikes a medal earned weeks ago as though it had just been won.
        if (state.isLoading) return@Scaffold

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

            // Keyed on the milestone, not on the state: a medal unlocked while this screen sits in
            // the back stack replaces the hero and strikes, where a card that merely recomposed
            // would swap the disc silently and skip the one moment worth watching.
            val latest = state.latest
            if (latest == null) {
                NoMedalsYetCard()
            } else {
                key(latest.achievement.id) { LatestMedalCard(entry = latest) }
            }

            MedalShelf(entries = state.entries)
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Achievements", showBackground = true, heightDp = 760)
@Composable
fun AchievementsScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        AchievementsScreen(state = populatedAchievements(), onBack = {})
    }
}

/**
 * Every label at twice its size — the bar #40 holds every screen to, on the pattern Home set.
 *
 * Recorded rather than argued about: the reference image is the evidence, it is a screenshot
 * test like any other, and a change that starts clipping or overlapping something at 2x fails
 * the build. Robolectric's font metrics are not a phone's, so the device walk in
 * docs/ACCESSIBILITY.md is still the result; this is what keeps it from rotting.
 */
@Preview(name = "Achievements - twice the font", showBackground = true, heightDp = 1400, fontScale = 2f)
@Composable
fun AchievementsScreenLargeFontPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        AchievementsScreen(state = populatedAchievements(), onBack = {})
    }
}

@Preview(name = "Achievements - dark", showBackground = true, heightDp = 760)
@Composable
fun AchievementsScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        AchievementsScreen(state = populatedAchievements(), onBack = {})
    }
}

@Preview(name = "Achievements - none held", showBackground = true, heightDp = 760)
@Composable
fun AchievementsScreenEmptyPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        AchievementsScreen(state = emptyAchievements(), onBack = {})
    }
}
