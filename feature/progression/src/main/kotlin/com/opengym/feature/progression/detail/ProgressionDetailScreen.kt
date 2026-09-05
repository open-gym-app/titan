package com.opengym.feature.progression.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.ui.component.EmptyState
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R

/**
 * The four screens the stats hub drills into, before the four issues that build them land.
 *
 * ### Why they exist now rather than with their own issues
 *
 * The hub's explore section is the **only** way into two of them: the bottom bar is four tabs
 * on purpose (see `TopLevelDestination`), so the quest log (#104) and the body measurements
 * screen (#109) have no other entrance in the app. #32's acceptance criteria say every explore
 * row navigates to the correct destination, and a row that ripples and does nothing is worse
 * than no row at all — it is a control that lied.
 *
 * So the *destinations* ship with the hub and the *screens* do not. Each is one page saying
 * what belongs there and which issue is building it, and each is replaced wholesale by that
 * issue: #103 the PR list, #104 the quest log. Per-exercise progression (#102) and body
 * measurements (#109) have already been through that and are gone from here, which is what the
 * arrangement was for — replacing one is a change to one entry and one registration.
 *
 * ### Full screen, not inside the tab
 *
 * Registered outside the tab scaffold, like the exercise detail and the plate calculator, and
 * for the same reason: all four wireframes draw a reading surface with its own bar and a way
 * back. The Stats title and a bottom bar offering a jump to Home are not what somebody looking
 * at a list of personal records needs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressionDetailScreen(
    detail: ProgressionDetail,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // Outside the tab scaffold, so this screen is the one that insets itself — unlike
        // `StatsScreen`, whose shell has already spent the system bars' space.
        contentWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(detail.titleRes)) },
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
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            EmptyState(
                title = stringResource(detail.titleRes),
                description = stringResource(detail.bodyRes),
                glyph = stringResource(R.string.progression_detail_glyph),
            )
        }
    }
}

/**
 * Which of the remaining drill-downs this page is standing in for.
 *
 * An enum rather than a screen each, because the page is identical apart from two strings and
 * a copy of it per drill-down would be a file to delete instead of an entry to remove. Each
 * entry names the issue that replaces it, so a reader who lands here knows where the real
 * screen is being built — #102, #103 and #109 have already taken theirs out, which is the
 * arrangement working. When the last one goes, so does this file.
 */
enum class ProgressionDetail(
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
) {
    /** #104 — level, XP, quests, achievements and loot. */
    QUEST_LOG(R.string.explore_quests_title, R.string.progression_detail_quests_body),
}

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Progression detail", showBackground = true, widthDp = 360, heightDp = 500)
@Composable
fun ProgressionDetailScreenPreview() {
    OpenGymTheme(theme = ThemePreference.LIGHT) {
        ProgressionDetailScreen(detail = ProgressionDetail.QUEST_LOG, onBack = {})
    }
}
