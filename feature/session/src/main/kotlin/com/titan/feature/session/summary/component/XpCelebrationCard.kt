package com.titan.feature.session.summary.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.titan.core.ui.component.ConfettiBurst
import com.titan.core.ui.component.CountUpNumber
import com.titan.core.ui.component.Eyebrow
import com.titan.core.ui.component.HairlineDivider
import com.titan.core.ui.component.LevelCeremony
import com.titan.core.ui.component.LevelProgressBar
import com.titan.core.ui.component.LevelSweep
import com.titan.core.ui.component.TitanCard
import com.titan.core.ui.component.rememberLevelSweep
import com.titan.core.ui.theme.TitanTheme
import com.titan.feature.session.R
import com.titan.feature.session.summary.SummaryCelebration
import com.titan.feature.session.summary.XpSource
import com.titan.feature.session.summary.XpSourceKind
import kotlin.math.roundToInt

/**
 * What the workout paid: the points, the level they moved, and what earned them.
 *
 * ### Why this is allowed to be the loudest thing on the screen
 *
 * `NewRecordsCard` documents the rule it depends on — there is never a second *accented* card
 * on this recap, because a screen with two things shouting has none. This card does not break
 * that rule, it sits outside it: the level-up banner is filled in the celebration palette's
 * violet, not the accent, and `DONE` keeps the only filled accent on the page. See
 * `TitanCelebrationColors`, which argues for why a second register exists at all and why it
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
    // One sweep drives the banner, the bar and the figure beside it, so the three cannot
    // disagree about which level is being counted into at any moment.
    val sweep = rememberLevelSweep(
        fromLevel = celebration.levelBefore,
        fromFraction = celebration.levelProgressBefore,
        toLevel = celebration.level,
        toFraction = celebration.levelProgress,
    )

    TitanCard(modifier = modifier.fillMaxWidth()) {
        Column {
            if (celebration.leveledUp) {
                LevelUpBanner(sweep = sweep)
            }

            Column(
                modifier = Modifier.padding(TitanTheme.spacing.card),
                verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.lg),
            ) {
                XpEarned(xp = celebration.xpAwarded)

                LevelStanding(celebration = celebration, sweep = sweep)

                if (celebration.sources.isNotEmpty()) {
                    HairlineDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.sm)) {
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
 * The number is the sweep's, not the session's final level: it ticks over as each threshold is
 * passed, so a first workout worth four levels is watched climbing rather than reported as having
 * arrived. See [rememberLevelSweep].
 *
 * ### One flourish per threshold
 *
 * The number kicks and a fresh burst falls on every crossing, both keyed on
 * [LevelSweep.crossings]. Keyed rather than fired once at the end, because the whole point is that
 * four levels are four events — a single burst over a run of four would celebrate the arithmetic
 * instead of the climb.
 *
 * Both are guarded on there having *been* a crossing. The banner is on screen from the first
 * frame, showing the level the session started on, and the sweep does not reach the first
 * threshold for the better part of a second — so an unguarded flourish fires over the old
 * number, and a one-level session throws two bursts for one event.
 *
 * The confetti is drawn *behind* the words: a flourish that obscures the one sentence it is
 * celebrating has inverted its own purpose. It draws nothing at all under reduced motion, where
 * `crossings` never advances either — see `ConfettiBurst`.
 */
@Composable
private fun LevelUpBanner(
    sweep: LevelSweep,
    modifier: Modifier = Modifier,
) {
    val celebrationColors = TitanTheme.celebration

    // The kick: snapped large the instant a threshold is passed, then sprung back. Snapping
    // rather than animating *into* the peak is what makes it land on the beat the sweep just
    // held for, instead of trailing behind it.
    //
    // How large is the ceremony's, not this file's — a milestone kicks harder and throws more,
    // and a future tier will do more again without either number being written here. See
    // [LevelCeremony].
    val kick = remember { Animatable(1f) }
    LaunchedEffect(sweep.crossings) {
        if (sweep.crossings == 0) return@LaunchedEffect
        kick.snapTo(sweep.ceremony.kick)
        kick.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }

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
        // `key` gives each crossing its own burst: it discards the previous one's state, which is
        // what lets a component that deliberately plays once play again for the next threshold.
        // Not composed at all until the first threshold — see the note above.
        if (sweep.crossings > 0) {
            key(sweep.crossings) {
                ConfettiBurst(
                    colors = celebrationColors.confetti,
                    pieceCount = sweep.ceremony.confettiPieces,
                    style = sweep.ceremony.confettiStyle,
                    seed = sweep.crossings,
                    // `matchParentSize`, not `fillMaxSize`. The banner's height comes from the
                    // column of text below this, and inside a `LazyColumn` item the incoming
                    // height constraint is unbounded — so `fillMaxSize` has nothing to expand
                    // against and the canvas measures to nothing. Every piece then lands at y=0
                    // and the burst draws as a row of dots welded to the top edge.
                    // `matchParentSize` is measured after the parent's size is known and does
                    // not feed into it, which is exactly the relationship a backdrop wants.
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TitanTheme.spacing.card),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
        ) {
            Eyebrow(
                text = stringResource(
                    // A milestone says so, because a crossing that is only *louder* reads as
                    // the same event with the volume up. Naming it is what makes it a different
                    // one.
                    if (sweep.ceremony == LevelCeremony.MILESTONE) {
                        R.string.summary_level_milestone
                    } else {
                        R.string.summary_level_up
                    },
                ),
                color = celebrationColors.onLevelUp,
            )
            Text(
                text = stringResource(R.string.summary_level_number, sweep.level),
                style = TitanTheme.typography.numericDisplay,
                color = celebrationColors.onLevelUp,
                modifier = Modifier.graphicsLayer {
                    scaleX = kick.value
                    scaleY = kick.value
                },
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
    // The running digits go through the same resource the breakdown rows use, rather than
    // through `Int.toString()`: a locale that renders its own numerals would otherwise draw the
    // headline in Latin digits and the `+70` two lines under it in the user's own.
    val resources = LocalContext.current.resources

    // No eyebrow of its own: the section this card sits in is already headed `XP EARNED`, and
    // the label repeated inside it was the same words twice in fourteen vertical pixels.
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            CountUpNumber(
                value = xp,
                // The final figure, spoken once — a running integer read aloud sixty times a
                // second is the accessibility failure this component exists to avoid.
                contentDescription = pluralStringResource(R.plurals.summary_xp_earned_description, xp, xp),
                style = TitanTheme.typography.numericDisplay,
                color = TitanTheme.celebration.xp,
                format = { resources.getString(R.string.summary_xp_source_amount, it) },
            )
            Text(
                text = stringResource(R.string.summary_xp_unit),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = TitanTheme.spacing.xs, bottom = TitanTheme.spacing.xs)
                    // The figure beside it already says "XP" to a screen reader.
                    .clearAndSetSemantics { },
            )
        }
    }
}

/**
 * Where the user stands, as the sweep counts them there.
 *
 * The figure beside the bar follows it rather than showing the final answer, through a
 * `derivedStateOf` over the whole points — so it recomposes at most once per point rather than
 * once per frame, and reads as a tally running up beside the bar it belongs to.
 *
 * The spoken sentence, by contrast, is the **outcome**: a screen reader is given the level and
 * the points remaining once, not a running commentary on an animation it cannot see.
 */
@Composable
private fun LevelStanding(
    celebration: SummaryCelebration,
    sweep: LevelSweep,
    modifier: Modifier = Modifier,
) {
    val remaining = celebration.xpForLevel - celebration.xpIntoLevel
    val into by remember(sweep, celebration.xpForLevel) {
        derivedStateOf { (sweep.fraction.value * celebration.xpForLevel).roundToInt() }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(TitanTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.summary_level_number, sweep.level),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.summary_level_progress, into, celebration.xpForLevel),
                style = TitanTheme.typography.numericSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LevelProgressBar(
            // A lambda, so the sweep repaints the bar without recomposing this row.
            progress = { sweep.fraction.value },
            // The bar's own sentence carries the level and what is left, because the two lines
            // above it are separate nodes and a reader arriving at the bar alone would hear a
            // proportion with nothing to attach it to.
            contentDescription = pluralStringResource(
                R.plurals.summary_level_progress_description,
                remaining,
                celebration.level,
                remaining,
            ),
            color = TitanTheme.celebration.xp,
            trackColor = TitanTheme.celebration.xpTrack,
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
            style = TitanTheme.typography.numericSmall,
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

        XpSourceKind.QUESTS -> pluralStringResource(R.plurals.summary_xp_source_quests, count, count)
    }
