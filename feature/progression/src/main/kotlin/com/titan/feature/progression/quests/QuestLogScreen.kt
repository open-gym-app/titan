package com.titan.feature.progression.quests

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EmojiEvents
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.IconTile
import com.titan.core.ui.component.SectionHeading
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.component.TitanTabRow
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.progression.R
import com.titan.feature.progression.quests.component.ClearedQuestRow
import com.titan.feature.progression.quests.component.LevelHeroCard
import com.titan.feature.progression.quests.component.QuestPeriodHeader
import com.titan.feature.progression.quests.component.QuestRow
import com.titan.feature.progression.quests.component.RecentLootCard
import com.titan.feature.progression.quests.component.StreakCard
import kotlinx.coroutines.launch

@Composable
fun QuestLogRoute(
    onBack: () -> Unit,
    onOpenAchievements: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QuestLogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    QuestLogScreen(
        state = state,
        onBack = onBack,
        onOpenAchievements = onOpenAchievements,
        modifier = modifier,
    )
}

/**
 * The quest board: level and rank, streak, and what there is to clear this week and this month.
 *
 * ### One screen, kept off the bottom bar
 *
 * The whole point of #32's split is that gamification lives in one place a user can ignore rather
 * than being woven through the app. It is reached from the stats hub's explore section and from
 * nowhere else — the bottom bar is four tabs on purpose; see `TopLevelDestination`.
 *
 * ### Achievements are not quests, and no longer share this screen
 *
 * They were one grid at the bottom of this board, and the two read as one idea because they were
 * drawn as one. They are not: a quest **resets** — it has a deadline, partial progress and a
 * reward that is paid again next period — while an achievement is a permanent, one-shot unlock.
 * Putting a rolling weekly target beside "first workout logged" made both of them harder to
 * understand, and it left this screen long enough that the quests it is named for started below
 * the fold. They have their own screen now; see `AchievementsScreen`. What stays here is a single
 * row saying how many are held, which is the one thing about them worth knowing from the board.
 *
 * ### The zero state is the default, not a branch
 *
 * There is no "empty screen" here. A brand-new user gets level 1 with an empty ring, an invitation
 * to start a streak, and every quest at zero — which is a real, correct and rather encouraging
 * screen, and is what #104 asks for. The only thing gated on loading is the claim that a build has
 * no quests at all; see [QuestLogUiState.showsEmptyQuests].
 *
 * ### The order is what the user came for, then what happened to them
 *
 * Level, streak, quests, milestones, loot. The first four are things the user is *doing* — a rank
 * they are climbing, a run they are keeping, a board to clear, a count to raise — and the board is
 * placed above the milestone row because it is the only part of this screen that expires. Loot is
 * last because it is the one section the user cannot act on at all: a drop is rolled by the app
 * when a workout ends, so it is a reward to notice on the way past rather than a thing to go and
 * get. It is also the only section that is absent rather than empty when there is nothing in it —
 * see [QuestLogUiState.showsLoot].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuestLogScreen(
    state: QuestLogUiState,
    onBack: () -> Unit,
    onOpenAchievements: () -> Unit,
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
                    horizontal = TitanTheme.spacing.screen,
                    vertical = TitanTheme.spacing.md,
                ),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xl),
        ) {
            LevelHeroCard(progress = state.progress, title = state.levelTitle)

            StreakCard(
                progress = state.progress,
                isLive = state.isStreakLive,
                isOnBest = state.isOnBestStreak,
                protection = state.protection,
            )

            QuestSections(state = state)

            AchievementsLink(
                unlocked = state.achievementsUnlocked,
                total = state.achievementsTotal,
                onClick = onOpenAchievements,
            )

            if (state.showsLoot) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
                ) {
                    SectionHeading(text = stringResource(R.string.quest_log_loot_heading))
                    RecentLootCard(drops = state.recentLoot)
                }
            }
        }
    }
}

/**
 * The board, one **tab** per cadence, swipeable.
 *
 * ### Why the two cadences stopped being stacked
 *
 * They were two headed groups down one column, and the month's quests were below the fold on
 * every phone — reachable only by scrolling past a week the user had usually already read. Two
 * deadlines are two contexts, not two paragraphs: a tab makes each one a whole screen's worth of
 * attention, and swiping between them costs nothing.
 *
 * The score card moved *inside* the tab, because it is a fact about the cadence rather than about
 * the board.
 *
 * ### Both pages stay composed
 *
 * [PagerDefaults] would compose only what is on screen, and the pager wraps its height to the
 * pages it has — so a swipe from a four-quest week to a three-quest month would resize the whole
 * screen mid-gesture, under the thumb doing the swiping. `beyondViewportPageCount = 1` holds both,
 * so the height is the taller of the two and constant. The cost is a little space under the
 * shorter tab, which is the cheaper half of that trade.
 *
 * A cadence with no quests produces no tab at all — see [QuestLogUiState.boards].
 */
@Composable
private fun QuestSections(
    state: QuestLogUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
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

        val boards = state.boards
        if (boards.isEmpty()) return@Column

        val pager = rememberPagerState { boards.size }
        val scope = rememberCoroutineScope()

        TitanTabRow(
            labels = boards.map { it.period.heading() },
            selectedIndex = pager.currentPage,
            // Animated rather than snapped, so tapping a tab and swiping to it are the same
            // move seen twice — a tab that jumps while a swipe glides reads as two features.
            onSelect = { page -> scope.launch { pager.animateScrollToPage(page) } },
        )

        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
        ) { page ->
            QuestGroup(board = boards[page])
        }
    }
}

/**
 * One cadence: its score, the quests still open, and the ones already banked underneath.
 *
 * The cleared group is drawn *after* the live one and behind a label of its own, which is the
 * split #104's review asked for. It is absent rather than empty on a board with nothing cleared
 * yet — a "Cleared" heading over nothing is the screen listing what the user has not done.
 */
@Composable
private fun QuestGroup(
    board: QuestPeriodBoard,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
    ) {
        QuestPeriodHeader(board = board)

        board.live.forEachIndexed { index, row ->
            QuestRow(row = row)
            if (index != board.live.lastIndex) {
                HairlineDivider()
            }
        }

        if (board.cleared.isNotEmpty()) {
            Text(
                text = stringResource(R.string.quest_log_quests_cleared, board.cleared.size),
                style = TitanTheme.typography.eyebrow,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = TitanTheme.spacing.sm),
            )
            board.cleared.forEach { row -> ClearedQuestRow(row = row) }
        }
    }
}

/**
 * The way to the achievements, as one row saying how many are held.
 *
 * A count and an arrow rather than a preview of the grid: an achievement is a permanent unlock
 * with no deadline, so there is nothing about it that needs acting on today — which is exactly
 * why it is a link off this screen rather than a section on it. The figure is what a user
 * glancing at the board wants: `3 / 6`, and a way through when that is interesting.
 */
@Composable
private fun AchievementsLink(
    unlocked: Int,
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = stringResource(R.string.quest_log_achievements_count, unlocked, total)
    val spoken = stringResource(R.string.quest_log_achievements_link_description, unlocked, total)

    TitanCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TitanTheme.spacing.card),
            horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon = Icons.Outlined.EmojiEvents)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.quest_log_achievements_heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.quest_log_achievements_link_caption),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = count,
                style = TitanTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Quest log", showBackground = true, heightDp = 1400)
@Composable
fun QuestLogScreenPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        QuestLogScreen(state = populatedQuestLog(), onBack = {}, onOpenAchievements = {})
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
@Preview(name = "Quest log - twice the font", showBackground = true, heightDp = 2400, fontScale = 2f)
@Composable
fun QuestLogScreenLargeFontPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        QuestLogScreen(state = populatedQuestLog(), onBack = {}, onOpenAchievements = {})
    }
}

@Preview(name = "Quest log - dark", showBackground = true, heightDp = 1400)
@Composable
fun QuestLogScreenDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        QuestLogScreen(state = populatedQuestLog(), onBack = {}, onOpenAchievements = {})
    }
}

@Preview(name = "Quest log - brand new", showBackground = true, heightDp = 1400)
@Composable
fun QuestLogScreenEmptyPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        QuestLogScreen(state = emptyQuestLog(), onBack = {}, onOpenAchievements = {})
    }
}

@Preview(name = "Quest log - just levelled up", showBackground = true, heightDp = 1400)
@Composable
fun QuestLogScreenLevelledUpPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        QuestLogScreen(state = justLevelledUpQuestLog(), onBack = {}, onOpenAchievements = {})
    }
}
