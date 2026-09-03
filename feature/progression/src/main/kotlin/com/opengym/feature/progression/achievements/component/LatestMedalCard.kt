package com.opengym.feature.progression.achievements.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import com.opengym.core.domain.gamification.AchievementTier
import com.opengym.core.domain.model.AchievementMetric
import com.opengym.core.ui.component.ACHIEVEMENT_MEDAL_HERO_SIZE
import com.opengym.core.ui.component.AchievementMedal
import com.opengym.core.ui.component.ConfettiBurst
import com.opengym.core.ui.component.ConfettiStyle
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.component.rememberMedalCeremony
import com.opengym.core.ui.format.formatDayAndMonth
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.achievements.AchievementEntry
import com.opengym.feature.progression.achievements.label
import kotlinx.coroutines.launch

/**
 * The newest medal, struck again as the screen opens.
 *
 * ### Why the screen has a hero at all
 *
 * A shelf of six identical discs answers "what is there" and not "what did I just do", and the
 * second is the question somebody opening this screen after a workout actually has. Leading with
 * the most recent unlock answers it before they have read anything — and it gives the ceremony
 * somewhere to play at a size worth watching, which a 84dp cell on a shelf does not.
 *
 * It leads with the *newest* rather than the *rarest* deliberately; see
 * [com.opengym.feature.progression.achievements.AchievementsUiState.latest].
 *
 * ### The confetti belongs to the medal, not to the screen
 *
 * It is thrown from behind the disc, sized to the card, and re-thrown on every replay — keyed on
 * [com.opengym.core.ui.component.MedalCeremony.plays], since a burst plays once per composition
 * and would otherwise sit still on the second tap. `BURST` rather than `RAIN`: the milestone is
 * arriving *here*, and rain falling past it would put the event somewhere above the medal.
 *
 * Under reduced motion the ceremony lands immediately and [ConfettiBurst] draws nothing at all, so
 * this card is the medal, its name and its date — which is the whole of the information in it.
 */
@Composable
internal fun LatestMedalCard(
    entry: AchievementEntry,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val ceremony = rememberMedalCeremony(autoPlay = true)
    val spoken = stringResource(R.string.achievements_latest_eyebrow) + ", " + entry.spokenState()
    val replayLabel = stringResource(R.string.achievements_replay_action)
    val interactions = remember { MutableInteractionSource() }

    OpenGymCard(
        modifier = modifier
            .fillMaxWidth()
            // No ripple, for the reason `MedalShelf` gives: the strike is the feedback.
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClickLabel = replayLabel,
                onClick = { scope.launch { ceremony.play() } },
            )
            .clearAndSetSemantics { contentDescription = spoken },
        // The one live thing on the screen, which is what an accented card is for.
        accented = true,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Sized by the column beside it rather than by itself: a burst measured to nothing
            // draws a row of stuck dots. See `ConfettiBurst`.
            key(ceremony.plays) {
                ConfettiBurst(
                    colors = OpenGymTheme.celebration.confetti,
                    style = ConfettiStyle.BURST,
                    modifier = Modifier.matchParentSize(),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(OpenGymTheme.spacing.card),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
            ) {
                Eyebrow(text = stringResource(R.string.achievements_latest_eyebrow))

                AchievementMedal(
                    tier = entry.tier,
                    metric = entry.achievement.metric,
                    isUnlocked = true,
                    size = ACHIEVEMENT_MEDAL_HERO_SIZE,
                    ceremony = ceremony,
                )

                Text(
                    text = entry.achievement.label(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                entry.unlockedAt?.let { earnedAt ->
                    Text(
                        text = stringResource(
                            R.string.achievements_latest_meta,
                            entry.tier.label(),
                            formatDayAndMonth(earnedAt),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The hero before there is one: a blank disc, and the one sentence that fills it.
 *
 * Not an empty state and drawn in the hero's own place, because the screen is not empty — the
 * shelf below is the whole catalogue, waiting. What this replaces is the card that would have
 * celebrated something, so it says what strikes the first medal and stops. No call to action: the
 * button that starts a workout lives on Home, and a second one here would be this screen asking
 * to be somewhere else.
 */
@Composable
internal fun NoMedalsYetCard(modifier: Modifier = Modifier) {
    val title = stringResource(R.string.achievements_none_title)
    val body = stringResource(R.string.achievements_none_body)

    OpenGymCard(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "$title. $body" },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.card),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            AchievementMedal(
                // Bronze, though nothing is drawn in it: a locked medal is neutral whatever its
                // metal, and the first rung of every ladder is where this user is standing.
                tier = AchievementTier.BRONZE,
                metric = AchievementMetric.SESSIONS_FINISHED,
                isUnlocked = false,
                size = ACHIEVEMENT_MEDAL_HERO_SIZE,
            )

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
