package com.opengym.feature.progression.quests.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import com.opengym.core.domain.model.Rarity
import com.opengym.core.ui.component.BadgeTone
import com.opengym.core.ui.component.ICON_TILE_SIZE
import com.opengym.core.ui.component.IconTile
import com.opengym.core.ui.component.OpenGymBadge
import com.opengym.core.ui.component.OpenGymCard
import com.opengym.core.ui.format.formatDayAndMonth
import com.opengym.core.ui.theme.OpenGymTheme
import com.opengym.feature.progression.R
import com.opengym.feature.progression.quests.LootDrop
import com.opengym.feature.progression.quests.dropSourceLabel
import com.opengym.feature.progression.quests.label

/**
 * The latest drops: what fell out, how rare it was, and what produced it.
 *
 * ### Rarity is a word and an emphasis, not a colour ramp
 *
 * The obvious drawing of a five-band rarity ladder is five hues, and this app does not have them
 * to spend. #86's rule is that there is one accent and that everything which matters is drawn in
 * it; a second hue that survives past the celebration screen is a bug in the design system. Five
 * would make the accent mean "decorated" rather than "this is the thing", and a common drop tinted
 * grey-blue and an epic one tinted purple would be the app's only place where a hue encodes a
 * quantity.
 *
 * So the band is carried by its **name** — which is exact, translatable and reaches a screen
 * reader for free — and the accent marks the single binary question the eye actually asks across a
 * list of three: *is this one worth looking at*. [Rarity] is an ordered enum precisely so that
 * "rarer than" is a comparison (its KDoc says so, naming this screen), and the threshold is stated
 * once in [isNotable].
 *
 * `OpenGymPlateColors` is not a precedent for doing otherwise. That ramp is *functional* — it
 * matches the colours of real plates so a loaded barbell can be read at a glance — and a drop has
 * no such counterpart in the world.
 *
 * ### One tile, not eight
 *
 * Every drop takes the same mark. Eight collectibles would be eight icons to draw, none of which
 * exists, and a set of near-identical glyphs would say less than one consistent one: the row's
 * information is in its words. The tile follows the rarity's emphasis so the section still has a
 * shape when read at arm's length.
 *
 * ### Each row speaks once
 *
 * A drop is one fact — "Pet egg, rare, from a finished workout, 24 APR" — and the four pieces are
 * four nodes to swipe past if left alone. The row replaces them wholesale, the call
 * [StreakCard] and `TrainingCalendar` both already make.
 */
@Composable
internal fun RecentLootCard(
    drops: List<LootDrop>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.sm),
    ) {
        for (drop in drops) {
            LootRow(drop = drop)
        }
    }
}

@Composable
private fun LootRow(drop: LootDrop) {
    val rarity = drop.collectible?.rarity
    val notable = rarity.isNotable()

    val name = drop.collectible.label()
    val band = rarity.label()
    val source = dropSourceLabel(drop.item.source)
    val date = formatDayAndMonth(drop.item.acquiredAt.toEpochMilli())
    // Resolved out here: the semantics lambda below is not composable, and the four parts join
    // in a translatable resource rather than with a comma written into Kotlin.
    val spoken = stringResource(R.string.quest_log_loot_description, name, band, source, date)

    // Not an `accented` card, though it would be the obvious third place to say "notable". The
    // accented border is the hero's signal on this screen — `LevelHeroCard` is the only card
    // wearing it — and three glowing rows under it would take that meaning away from the thing
    // the screen is actually about. The tile and the band badge carry the emphasis between them.
    OpenGymCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(OpenGymTheme.spacing.screen)
                .clearAndSetSemantics { contentDescription = spoken },
            horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(
                icon = Icons.Outlined.Diamond,
                size = ICON_TILE_SIZE,
                tint = if (notable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                container = if (notable) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )

            // The band sits on the name's line rather than at the end of the row, which is what
            // leaves the line under it the whole width. Beside the tile it was a third column,
            // and the source and the date — the two things #104 actually asks this section to
            // show — were the half that got ellipsed away on a 360dp screen.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.xs),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(OpenGymTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    OpenGymBadge(
                        text = band,
                        tone = if (notable) BadgeTone.ACCENT else BadgeTone.NEUTRAL,
                    )
                }
                Text(
                    text = stringResource(R.string.quest_log_loot_meta, source, date),
                    style = OpenGymTheme.typography.numericSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Whether a band is one worth marking in the accent.
 *
 * `RARE` and up, as a *comparison* rather than a set — see [Rarity], whose declaration order is
 * the ladder and whose KDoc names this screen as the reason it is ordered. A band inserted into
 * the middle of that enum therefore moves this threshold with it, which is the point: the question
 * is "is this near the top", not "is it one of these three names".
 *
 * An unplaceable drop (null) is not notable. Nothing is known about it, and drawing it in the
 * accent would be the screen guessing in the user's favour about loot it cannot even name.
 */
private fun Rarity?.isNotable(): Boolean = this != null && this >= Rarity.RARE
