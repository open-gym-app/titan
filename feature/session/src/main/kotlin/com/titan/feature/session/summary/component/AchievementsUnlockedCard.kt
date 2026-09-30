package com.titan.feature.session.summary.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.titan.core.domain.gamification.AchievementTier
import com.titan.core.domain.gamification.Achievements
import com.titan.core.domain.gamification.tier
import com.titan.core.domain.model.AchievementMetric
import com.titan.core.ui.component.ACHIEVEMENT_MEDAL_COMPACT_SIZE
import com.titan.core.ui.component.AchievementMedal
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.component.rememberMedalCeremony
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.session.R
import com.titan.feature.session.summary.SummaryAchievement

/**
 * The milestones this session unlocked, one row each, each medal struck as the recap opens.
 *
 * ### Why they are not folded into the XP breakdown
 *
 * The breakdown already carries an `achievements` line — but it says only *how much they paid*,
 * which is the one thing about an achievement nobody cares about. What the user wants to know
 * is **which** one they got, and that is a name rather than a number. So the points appear
 * twice on this screen, in two different roles: as a contribution to the total above, and as a
 * footnote to the thing that was actually earned here.
 *
 * ### A milestone this build does not recognise still draws
 *
 * The catalogue is `Achievements.ALL` in the domain and the words live here, so an id with no
 * string is a real possibility — a row written by a newer build, read by an older one. It draws
 * with a fallback title rather than being dropped, on the same reasoning `SummaryRecord` uses
 * for an exercise gone from the catalogue: it is a thing the user did, and a screen that
 * silently omitted it would be under-reporting their session.
 *
 * The caller draws nothing when the list is empty. Most sessions unlock nothing, and a card
 * saying so would be the screen listing what the user has not achieved.
 *
 * ### The medal is struck here, and this is the moment it is for
 *
 * The row used to carry a pill reading `MEDAL`, which was a label describing a thing rather than
 * the thing. It is now the same medal the achievements screen shelves — same metal, same glyph,
 * same ceremony — playing at the one moment the user has actually earned it. That the two surfaces
 * draw the identical object is the point: the disc that lands on the recap is the disc that is
 * waiting on the shelf afterwards, which is what makes the shelf worth opening.
 *
 * See `AchievementMedal` for the drawing and `MedalCeremony` for why an unlock is not the
 * level-up's animation at a different volume.
 */
@Composable
internal fun AchievementsUnlockedCard(
    achievements: List<SummaryAchievement>,
    modifier: Modifier = Modifier,
) {
    TitanCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(TitanTheme.spacing.card),
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
        ) {
            achievements.forEach { achievement ->
                AchievementRow(achievement = achievement)
            }
        }
    }
}

@Composable
private fun AchievementRow(
    achievement: SummaryAchievement,
    modifier: Modifier = Modifier,
) {
    val title = achievement.title()
    val celebrationColors = TitanTheme.celebration
    val catalogued = Achievements.ALL.firstOrNull { it.id == achievement.id }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AchievementMedal(
            // A milestone this build does not recognise still draws, on the same reasoning the
            // fallback title uses: it is a thing the user did. It gets the first rung's metal and
            // the trophy, which claims nothing about a ladder this build cannot see.
            tier = catalogued?.tier() ?: AchievementTier.BRONZE,
            metric = catalogued?.metric ?: AchievementMetric.RECORDS_BROKEN,
            isUnlocked = true,
            size = ACHIEVEMENT_MEDAL_COMPACT_SIZE,
            ceremony = rememberMedalCeremony(autoPlay = true),
        )

        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            // No semantics override: the title *is* the sentence, and replacing it with an
            // identical content description would only make the row harder to find by its text.
            modifier = Modifier.weight(1f),
        )

        Text(
            text = stringResource(R.string.summary_xp_source_amount, achievement.xp),
            style = TitanTheme.typography.numericSmall,
            color = celebrationColors.xp,
        )
    }
}

/**
 * The milestone's words, by id.
 *
 * A `when` over the ids rather than a name carried on `Achievement`: the domain's catalogue is a
 * plain Kotlin object with no `Context`, and putting user-visible English on it would make the
 * app untranslatable at the one place a translation is most obviously needed.
 */
@Composable
private fun SummaryAchievement.title(): String =
    when (id) {
        "first_session" -> stringResource(R.string.achievement_first_session)
        "ten_sessions" -> stringResource(R.string.achievement_ten_sessions)
        "hundred_sessions" -> stringResource(R.string.achievement_hundred_sessions)
        "seven_day_streak" -> stringResource(R.string.achievement_seven_day_streak)
        "thirty_day_streak" -> stringResource(R.string.achievement_thirty_day_streak)
        "first_record" -> stringResource(R.string.achievement_first_record)
        else -> stringResource(R.string.achievement_unknown)
    }
