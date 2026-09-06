package com.opengym.feature.progression.quests.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.opengym.core.ui.component.ConfettiBurst
import com.opengym.core.ui.component.ConfettiStyle
import com.opengym.core.ui.component.LevelProgressBar
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.theme.LocalReducedMotion
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.quests.QuestPeriodBoard
import com.opengym.feature.progression.quests.heading

/**
 * How a cadence is going, as the card that heads its group: `2 / 4`, and what is left on it.
 *
 * ### The board needed a score
 *
 * The first version of this screen went straight from a section heading into seven rows, and a
 * user could only answer "how is my week going?" by reading all of them and doing the counting
 * themselves. A period is a thing you are part-way through, and part-way through is a *figure* —
 * so it is drawn as one, large, with the bar that makes it a shape as well as a number.
 *
 * ### The subtitle is what is still available, not what is done
 *
 * "170 XP still to earn" rather than "230 XP earned". Both are true and only one of them is
 * useful on a Wednesday: the board exists to be acted on, and the reason to open it is what is
 * still reachable before the period resets. Once nothing is left the line switches to saying so
 * outright — a cleared cadence should read as an achievement rather than as "0 XP still to
 * earn", which is the same fact phrased as a disappointment.
 *
 * ### A cleared cadence celebrates itself
 *
 * When every quest of the period is banked, the card throws a burst and the score kicks. It is
 * the rarest thing on this screen — a full week, or a full month — and the one state where the
 * board has nothing left to ask for, so it is the one place a flourish is not noise.
 *
 * It fires **once per arrival**, keyed on the period rather than on a timestamp: there is nothing
 * stored anywhere that says "you have already been congratulated for this week", and inventing one
 * would mean persisting a row for a confetti burst. Coming back to the screen and seeing it again
 * is the acceptable half of that trade; it draws nothing at all under reduced motion, which
 * `ConfettiBurst` enforces for itself.
 *
 * ### One node
 *
 * The figure, the words and the bar are one sentence to a screen reader. Three nodes here would
 * make a reader assemble "2", "This week" and a percentage into the statement the sighted user
 * gets in one glance.
 */
@Composable
internal fun QuestPeriodHeader(
    board: QuestPeriodBoard,
    modifier: Modifier = Modifier,
) {
    val heading = board.period.heading()
    // The spoken line says what the visible one says. A cleared cadence read out as "0 XP still
    // to earn" would be the same fact phrased as a disappointment — the exact reading the
    // subtitle below switches away from, and a reader who cannot see it deserves the switch too.
    val spoken = if (board.isCleared) {
        stringResource(
            R.string.quest_log_period_description_cleared,
            heading,
            board.clearedCount,
            board.total,
        )
    } else {
        pluralStringResource(
            R.plurals.quest_log_period_description,
            board.remainingXp,
            heading,
            board.clearedCount,
            board.total,
            board.remainingXp,
        )
    }

    val reducedMotion = LocalReducedMotion.current

    // Shares the row bars' timing, so the header and the rows under it fill together rather than
    // as two separate events. See `questBarSpec`.
    val fraction by animateFloatAsState(
        targetValue = board.fraction,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = HEADER_BAR_MILLIS),
        label = "period progress",
    )

    // The kick the score takes on a cleared cadence: snapped large on arrival, then sprung back.
    // Snapping into the peak rather than animating to it is what makes it land on the beat, the
    // same shape the summary's level-up banner uses.
    val kick = remember { Animatable(1f) }
    LaunchedEffect(board.period, board.isCleared) {
        if (!board.isCleared || reducedMotion) return@LaunchedEffect
        kick.snapTo(CLEARED_KICK)
        kick.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }

    OpenGymCard(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Box {
            // Behind the words, never over them: a flourish that obscures the sentence it is
            // celebrating has inverted its own purpose. `matchParentSize` rather than
            // `fillMaxSize`, because inside a scrolling column the incoming height is unbounded
            // and a canvas told to fill it measures to nothing — see `XpCelebrationCard`.
            if (board.isCleared) {
                key(board.period) {
                    ConfettiBurst(
                        colors = OpenGymTheme.celebration.confetti,
                        style = ConfettiStyle.BURST,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(OpenGymTheme.spacing.card),
                verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.quest_log_period_score,
                            board.clearedCount,
                            board.total,
                        ),
                        style = OpenGymTheme.typography.numericDisplay,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.graphicsLayer {
                            scaleX = kick.value
                            scaleY = kick.value
                        },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = heading,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (board.isCleared) {
                                stringResource(R.string.quest_log_period_all_clear)
                            } else {
                                stringResource(R.string.quest_log_period_remaining, board.remainingXp)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                LevelProgressBar(
                    progress = { fraction },
                    // The card speaks as one node; this is never read out on its own.
                    contentDescription = spoken,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

private const val HEADER_BAR_MILLIS = 700

/** How large the score snaps before springing back, on a cadence that has just been cleared. */
private const val CLEARED_KICK = 1.35f
