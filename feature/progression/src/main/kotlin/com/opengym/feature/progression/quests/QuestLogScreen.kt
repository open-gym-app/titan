package com.opengym.feature.progression.quests

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
import com.opengym.core.domain.model.QuestPeriod
import com.opengym.core.domain.model.QuestProgress
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.SectionHeading
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.quests.component.AchievementGrid
import com.opengym.feature.progression.quests.component.LevelHeroCard
import com.opengym.feature.progression.quests.component.QuestRow
import com.opengym.feature.progression.quests.component.StreakCard

@Composable
fun QuestLogRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuestLogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    QuestLogScreen(state = state, onBack = onBack, modifier = modifier)
}

/**
 * The gamification surface: level and rank, streak, the quest board, and the achievements grid.
 *
 * ### One screen, kept off the bottom bar
 *
 * The whole point of #32's split is that gamification lives in one place a user can ignore rather
 * than being woven through the app. It is reached from the stats hub's explore section and from
 * nowhere else — the bottom bar is four tabs on purpose; see `TopLevelDestination`.
 *
 * ### The zero state is the default, not a branch
 *
 * There is no "empty screen" here. A brand-new user gets level 1 with an empty ring, an invitation
 * to start a streak, every quest at zero and every achievement locked — which is a real, correct
 * and rather encouraging screen, and is what #104 asks for. The only thing gated on loading is the
 * claim that a build has no quests at all; see [QuestLogUiState.showsEmptyQuests].
 *
 * ### What is missing, and deliberately not faked
 *
 * #104 also asks for a **recent loot** section (#M5-13) and **streak-protection status**
 * (#M5-12). Neither feature exists yet, and there is no partial version of either worth drawing:
 * an empty loot card would promise a feature the app does not have, and a protection state the
 * app cannot apply would tell the user their streak is safe when nothing is holding it. Both are
 * sections added with their features rather than placeholders added now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuestLogScreen(
    state: QuestLogUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // Outside the tab scaffold, like the other progression drill-downs, so the bar spends the
        // system bars' space and the body below does not spend it again.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.quest_log_title)) },
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
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xl),
        ) {
            LevelHeroCard(progress = state.progress, title = state.levelTitle)

            StreakCard(progress = state.progress, isOnBest = state.isOnBestStreak)

            QuestSections(state = state)

            AchievementSection(state = state)
        }
    }
}

/**
 * The board, split by cadence.
 *
 * Two headed groups rather than one list, because "this week" and "this month" are different
 * deadlines and a flat board would leave the user working out which is which from the wording of
 * each quest. The order within each group is the catalogue's — see `Quests.ALL`.
 *
 * A cadence with no quests draws no heading at all, rather than an empty group, which is what
 * keeps this correct for a build whose catalogue only has one of the two.
 */
@Composable
private fun QuestSections(
    state: QuestLogUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        SectionHeading(text = stringResource(R.string.quest_log_quests_heading))

        if (state.showsEmptyQuests) {
            Text(
                text = stringResource(R.string.quest_log_quests_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val byPeriod = state.quests.groupBy { it.quest.period }
        QuestPeriod.entries.forEach { period ->
            val rows = byPeriod[period].orEmpty()
            if (rows.isNotEmpty()) {
                QuestGroup(heading = period.heading(), rows = rows)
            }
        }
    }
}

@Composable
private fun QuestGroup(
    heading: String,
    rows: List<QuestProgress>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        rows.forEachIndexed { index, row ->
            QuestRow(row = row)
            if (index != rows.lastIndex) {
                HairlineDivider()
            }
        }
    }
}

@Composable
private fun AchievementSection(
    state: QuestLogUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
    ) {
        SectionHeading(
            text = stringResource(R.string.quest_log_achievements_heading),
            trailing = {
                Text(
                    text = stringResource(
                        R.string.quest_log_achievements_count,
                        state.unlockedCount,
                        state.achievements.size,
                    ),
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        AchievementGrid(entries = state.achievements)
    }
}

/** The cadence's words. A `when` here for the same reason the quest names are resolved here. */
@Composable
private fun QuestPeriod.heading(): String =
    when (this) {
        QuestPeriod.WEEKLY -> stringResource(R.string.quest_log_quests_weekly)
        QuestPeriod.MONTHLY -> stringResource(R.string.quest_log_quests_monthly)
    }

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Quest log", showBackground = true, heightDp = 1400)
@Composable
fun QuestLogScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        QuestLogScreen(state = populatedQuestLog(), onBack = {})
    }
}

@Preview(name = "Quest log - dark", showBackground = true, heightDp = 1400)
@Composable
fun QuestLogScreenDarkPreview() {
    OpenGymTheme(theme = ThemePreference.DARK) {
        QuestLogScreen(state = populatedQuestLog(), onBack = {})
    }
}

@Preview(name = "Quest log - brand new", showBackground = true, heightDp = 1400)
@Composable
fun QuestLogScreenEmptyPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        QuestLogScreen(state = emptyQuestLog(), onBack = {})
    }
}

@Preview(name = "Quest log - just levelled up", showBackground = true, heightDp = 1400)
@Composable
fun QuestLogScreenLevelledUpPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        QuestLogScreen(state = justLevelledUpQuestLog(), onBack = {})
    }
}
