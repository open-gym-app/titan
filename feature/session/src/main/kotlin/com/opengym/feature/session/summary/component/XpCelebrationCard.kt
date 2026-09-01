package com.opengym.feature.session.summary.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.opengym.core.ui.component.ConfettiBurst
import com.opengym.core.ui.component.CountUpNumber
import com.opengym.core.ui.component.Eyebrow
import com.opengym.core.ui.component.HairlineDivider
import com.opengym.core.ui.component.LevelProgressBar
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.session.R
import com.opengym.feature.session.summary.SummaryCelebration
import com.opengym.feature.session.summary.XpSource
import com.opengym.feature.session.summary.XpSourceKind

/**
 * What the workout paid: the points, the level they moved, and what earned them.
 *
 * ### Why this is allowed to be the loudest thing on the screen
 *
 * `NewRecordsCard` documents the rule it depends on — there is never a second *accented* card
 * on this recap, because a screen with two things shouting has none. This card does not break
 * that rule, it sits outside it: the level-up banner is filled in the celebration palette's
 * violet, not the accent, and `DONE` keeps the only filled accent on the page. See
 * `OpenGymCelebrationColors`, which argues for why a second register exists at all and why it
 * is confined to this moment.
 *
 * The XP figure itself *is* the accent, drawn as a figure rather than as a fill — the same
 * treatment a `PrValue` gets, for the same reason: it is a number that matters, not a surface.
 *
 * ### The banner only appears for a crossing
 *
 * A session that earned points without passing a threshold draws the figure, the bar and the
 * breakdown, and no banner. Levelling up is a different event from earning, and folding it into
 * the XP line — as a bigger number, or a colour — would make the rarest thing in the app look
 * like the most common one. #111 asks for exactly this separation.
 *
 * ### Nothing here is animated twice
 *
 * The count, the bar and the confetti each run once on first composition and are held across
 * configuration changes; every one of them lands immediately under reduced motion. They share
 * that contract deliberately, because they are read as a single moment and a rotation that
 * replayed one of the three would be the screen claiming the workout happened again.
 */
@Composable
internal fun XpCelebrationCard(
    celebration: SummaryCelebration,
    modifier: Modifier = Modifier,
) {
    OpenGymCard(modifier = modifier.fillMaxWidth()) {
        Column {
            if (celebration.leveledUp) {
                LevelUpBanner(level = celebration.level)
            }

            Column(
                modifier = Modifier.padding(OpenGymTheme.spacing.card),
                verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.lg),
            ) {
                XpEarned(xp = celebration.xpAwarded)

                LevelStanding(celebration = celebration)

                if (celebration.sources.isNotEmpty()) {
                    HairlineDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm)) {
                        celebration.sources.forEach { source -> XpSourceRow(source = source) }
                    }
                }
            }
        }
    }
}

/**
 * The crossing, as a filled band across the top of the card.
 *
 * The confetti is drawn *behind* the words rather than over them: a flourish that obscures the
 * one sentence it is celebrating has inverted its own purpose. It draws nothing at all under
 * reduced motion — see `ConfettiBurst`, which explains why this is the one degradation that
 * removes rather than stills.
 */
@Composable
private fun LevelUpBanner(
    level: Int,
    modifier: Modifier = Modifier,
) {
    val celebrationColors = OpenGymTheme.celebration

    Box(
        modifier = modifier
            .fillMaxWidth()
            // The card's own corner, squared off at the bottom where the band meets the
            // content. Derived rather than restated, so a change to the card's radius does not
            // leave a banner with corners that no longer fit it.
            .clip(
                MaterialTheme.shapes.medium.copy(
                    bottomStart = CornerSize(0.dp),
                    bottomEnd = CornerSize(0.dp),
                ),
            )
            .background(
                Brush.horizontalGradient(
                    listOf(celebrationColors.levelUp, celebrationColors.levelUpEdge),
                ),
            ),
    ) {
        ConfettiBurst(
            colors = celebrationColors.confetti,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.card),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
        ) {
            Eyebrow(
                text = stringResource(R.string.summary_level_up),
                color = celebrationColors.onLevelUp,
            )
            Text(
                text = stringResource(R.string.summary_level_number, level),
                style = OpenGymTheme.typography.numericDisplay,
                color = celebrationColors.onLevelUp,
            )
        }
    }
}

/** The points, counted up, with the unit set beside them rather than inside the figure. */
@Composable
private fun XpEarned(
    xp: Int,
    modifier: Modifier = Modifier,
) {
    // No eyebrow of its own: the section this card sits in is already headed `XP EARNED`, and
    // the label repeated inside it was the same words twice in fourteen vertical pixels.
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            CountUpNumber(
                value = xp,
                // The final figure, spoken once — a running integer read aloud sixty times a
                // second is the accessibility failure this component exists to avoid.
                contentDescription = stringResource(R.string.summary_xp_earned_description, xp),
                style = OpenGymTheme.typography.numericDisplay,
                color = OpenGymTheme.celebration.xp,
                format = { "+$it" },
            )
            Text(
                text = stringResource(R.string.summary_xp_unit),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = OpenGymTheme.spacing.xs, bottom = OpenGymTheme.spacing.xs)
                    // The figure beside it already says "XP" to a screen reader.
                    .clearAndSetSemantics { },
            )
        }
    }
}

/** Where the user now stands: the level, the bar, and how far into it they are. */
@Composable
private fun LevelStanding(
    celebration: SummaryCelebration,
    modifier: Modifier = Modifier,
) {
    val remaining = celebration.xpForLevel - celebration.xpIntoLevel

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.summary_level_number, celebration.level),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.summary_level_progress,
                    celebration.xpIntoLevel,
                    celebration.xpForLevel,
                ),
                style = OpenGymTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LevelProgressBar(
            progress = celebration.levelProgress,
            // The bar's own sentence carries the level and what is left, because the two lines
            // above it are separate nodes and a reader arriving at the bar alone would hear a
            // percentage with nothing to attach it to.
            contentDescription = pluralStringResource(
                R.plurals.summary_level_progress_description,
                remaining,
                celebration.level,
                remaining,
            ),
            color = OpenGymTheme.celebration.xp,
            trackColor = OpenGymTheme.celebration.xpTrack,
        )
    }
}

/** One line of the breakdown: what happened, how much of it, and what it paid. */
@Composable
private fun XpSourceRow(
    source: XpSource,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = source.label(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.summary_xp_source_amount, source.xp),
            style = OpenGymTheme.typography.numericSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The breakdown line's words — `12 sets`, `a record`.
 *
 * A plural resource per kind rather than one string with a noun substituted in, because the
 * count and the noun agree differently in different languages and a `%1$d %2$s` template is the
 * shape that cannot express that.
 */
@Composable
private fun XpSource.label(): String =
    when (kind) {
        XpSourceKind.SETS -> pluralStringResource(R.plurals.summary_xp_source_sets, count, count)

        XpSourceKind.SESSION -> stringResource(R.string.summary_xp_source_session)

        XpSourceKind.RECORDS -> pluralStringResource(R.plurals.summary_xp_source_records, count, count)

        XpSourceKind.ACHIEVEMENTS ->
            pluralStringResource(R.plurals.summary_xp_source_achievements, count, count)
    }
