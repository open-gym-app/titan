package com.titan.core.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titan.core.domain.gamification.AchievementTier
import com.titan.core.domain.model.AchievementMetric
import com.titan.core.domain.model.ThemePreference
import com.titan.core.ui.theme.LocalReducedMotion
import com.titan.core.ui.theme.TitanMedalColors
import com.titan.core.ui.theme.TitanTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * A struck medal: a faceted disc on a ribbon, in the metal its milestone is worth.
 *
 * ### Why the achievements are objects rather than rows
 *
 * They were cells of text — a name, and the word `Locked` under the ones not held — and the screen
 * read as a checklist of things the user had not done. That is the wrong reading of a milestone.
 * A quest is a list item, because it has a deadline and something to do about it today; an
 * achievement is a **thing you own**, permanently, and the grammar for owning things is objects on
 * a shelf. A locked cell in that grammar is a medal that has not been struck yet, which is an
 * invitation rather than an omission — and the moment it *is* struck, there is something to
 * watch. See [MedalCeremony].
 *
 * ### The metal carries the rank, and never alone
 *
 * [tier] comes from [com.titan.core.domain.gamification.tier], which reads it off the ladder
 * rather than from a table — so a new top rung takes the gold with no edit here. The podium
 * ordering needs no legend, but it is still colour carrying meaning, so it never carries it by
 * itself: every caller draws the milestone's name under the medal, and the tier is spoken in the
 * caller's own content description. A reader who sees no colour loses the ranking and keeps the
 * facts.
 *
 * ### The glyph says what was measured
 *
 * Sessions, streaks, records — the same three-way split the quest board's marks make, drawn from
 * the same closed enum so a fourth metric stops this compiling rather than shipping a blank disc.
 * A locked medal shows a padlock instead: the milestone's name is public, what is behind it is
 * the thing the user has not earned yet.
 *
 * **It says nothing.** No content description: every caller draws it beside or above the words it
 * illustrates, and a disc announcing itself between a name and its date is a decoration reading
 * itself out. A medal that must *mean* something on its own is being drawn by a caller that wants
 * a button.
 *
 * @param tier which metal. See [AchievementTier].
 * @param metric what the milestone counts — the glyph struck into the middle.
 * @param isUnlocked whether it is held. A locked medal is drawn in the neutral, with no ribbon
 *   and a padlock: the colour arriving is the event, so it cannot be there beforehand.
 * @param size the medal's box, ribbon included. [ACHIEVEMENT_MEDAL_SIZE] on a shelf,
 *   [ACHIEVEMENT_MEDAL_HERO_SIZE] for the one medal a screen is about.
 * @param ceremony the strike, when there is one to play. See [rememberMedalCeremony]; the default
 *   draws the finished medal and never animates.
 */
@Composable
fun AchievementMedal(
    tier: AchievementTier,
    metric: AchievementMetric,
    isUnlocked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = ACHIEVEMENT_MEDAL_SIZE,
    ceremony: MedalCeremony = NoCeremony,
) {
    val medal = TitanTheme.medal
    val palette = medal.of(tier = tier, isUnlocked = isUnlocked)
    val progress = ceremony.progress

    // The pop: a back-out curve that carries the disc past its own size and settles it. The
    // rotation runs on the same clock so the medal turns *as* it lands rather than after it,
    // which is the difference between a strike and a bounce.
    val entrance = POP_EASING.transform(((progress / POP_SHARE).coerceIn(0f, 1f)))
    val struck = progress >= 1f

    Box(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val radius = this.size.minDimension * DISC_RADIUS_SHARE
            val centre = Offset(x = this.size.width / 2f, y = this.size.height * DISC_CENTRE_Y)

            // Behind the disc, and only while the strike is playing: the rays and the shockwave
            // are the event, not the object, so a settled medal has neither.
            if (!struck) {
                drawRays(centre = centre, radius = radius, progress = progress, color = palette.face)
                drawShockwave(centre = centre, radius = radius, progress = progress, color = palette.face)
            }

            scale(scale = POP_FLOOR + (1f - POP_FLOOR) * entrance, pivot = centre) {
                rotate(degrees = (1f - entrance) * POP_TURN_DEGREES, pivot = centre) {
                    if (isUnlocked) {
                        drawRibbon(
                            centre = centre,
                            radius = radius,
                            bottom = this.size.height,
                            ribbon = medal.ribbon,
                            shade = medal.ribbonShade,
                        )
                    }
                    drawDisc(centre = centre, radius = radius, palette = palette)
                    if (!struck) {
                        drawShine(centre = centre, radius = radius, progress = progress, color = medal.shine)
                    }
                }
            }
        }

        Icon(
            imageVector = if (isUnlocked) metric.medalGlyph() else Icons.Outlined.Lock,
            contentDescription = null,
            tint = palette.glyph,
            modifier = Modifier
                .size(size * GLYPH_SHARE)
                // Lifted onto the disc, whose centre sits above the box's. An offset rather than
                // padding: this must not change what the icon measures to, only where it lands.
                .offset(y = -size * (HALF - DISC_CENTRE_Y))
                // The glyph rides the disc: struck into the metal, so it turns and grows with it
                // rather than hanging in front of a medal that is still arriving.
                .graphicsLayer(
                    scaleX = POP_FLOOR + (1f - POP_FLOOR) * entrance,
                    scaleY = POP_FLOOR + (1f - POP_FLOOR) * entrance,
                    rotationZ = (1f - entrance) * POP_TURN_DEGREES,
                ),
        )
    }
}

/**
 * The strike: the one-shot animation a medal plays when it is earned, or replayed on demand.
 *
 * ### Why an achievement gets its own ceremony rather than the level-up's
 *
 * `LevelCeremony` already answers "how much flourish is a crossing worth", and reusing it here was
 * the obvious move. It is the wrong one, because the two events are not the same shape. A level is
 * a **threshold passed** — the number was always going to get there, and the confetti marks the
 * moment it did. A milestone is an **object arriving**: it did not exist a second ago and now it
 * is on the shelf permanently. So this animation is about the medal, not about the screen: it
 * spins in and lands, a ring of light leaves it, and the metal catches the light once as it
 * settles. Nothing falls from the top of anything.
 *
 * The two are meant to be able to play together — a session that levels a user up *and* unlocks a
 * milestone gets both — which is a second reason they are separate clocks.
 *
 * ### Progress is one number, and everything is derived from it
 *
 * The pop, the rays, the shockwave and the shine are phases of a single `0f..1f`, not four
 * animations to keep in step. Anything that needs to be told when the medal has landed reads
 * [progress] rather than being called back, which is what lets a caller hang confetti off it — see
 * [plays], which is the key to re-throw a burst on a replay.
 *
 * ### Reduced motion lands it immediately
 *
 * [progress] is one from the start and [play] returns without animating: the medal is drawn
 * struck, with no rays, no shockwave and no sweep. The contract every animation in this codebase
 * is under — the outcome, not a faster ceremony. See [LocalReducedMotion].
 */
@Stable
class MedalCeremony internal constructor(
    private val animation: Animatable<Float, *>,
    private val reducedMotion: Boolean,
    plays: State<Int>,
    private val onPlayed: () -> Unit,
) {

    /** How far through the strike, `0f..1f`. One at rest, which is the finished medal. */
    val progress: Float get() = animation.value

    /**
     * How many times this medal has been struck in this composition.
     *
     * A key rather than a count worth reading: `key(ceremony.plays) { ConfettiBurst(...) }` is how
     * a caller re-throws a one-shot flourish on a replay, since [ConfettiBurst] deliberately plays
     * once per composition and would otherwise sit still.
     */
    val plays: Int by plays

    /**
     * Strike it, from the beginning, however far through a previous strike this is.
     *
     * Suspends until the medal has landed. Restarting rather than ignoring a second call is what
     * makes a medal worth tapping twice: the interruption reads as another strike, where letting
     * the first play out reads as a tap that did nothing.
     */
    suspend fun play() {
        if (reducedMotion) {
            animation.snapTo(1f)
            return
        }
        onPlayed()
        animation.snapTo(0f)
        animation.animateTo(targetValue = 1f, animationSpec = tween(STRIKE_MILLIS, easing = LinearEasing))
    }
}

/**
 * A ceremony for one medal, optionally struck the moment it is composed.
 *
 * @param autoPlay whether to strike on first composition. True for the medal a screen is *about* —
 *   the recap's just-earned milestone, the shelf's newest one; false for the rest of a shelf,
 *   where eighteen medals arriving at once is a screen having a seizure rather than an event.
 *   Held across configuration changes, so a rotation does not re-strike a medal the user has
 *   already watched land.
 */
@Composable
fun rememberMedalCeremony(autoPlay: Boolean = false): MedalCeremony {
    val reducedMotion = LocalReducedMotion.current

    // Struck already, or asked not to animate: seeded at the end, so the medal is simply there.
    var played by rememberSaveable { mutableStateOf(reducedMotion || !autoPlay) }
    val animation = remember { Animatable(if (played) 1f else 0f) }
    val plays = remember { mutableIntStateOf(0) }

    val ceremony = remember(reducedMotion) {
        MedalCeremony(
            animation = animation,
            reducedMotion = reducedMotion,
            plays = plays,
            onPlayed = { plays.intValue++ },
        )
    }

    // Guarded inside rather than keyed on: a medal restored as already struck must not strike
    // again when something above it recomposes.
    LaunchedEffect(Unit) {
        if (!played) {
            played = true
            ceremony.play()
        }
    }

    return ceremony
}

/** A ceremony that is over before it starts — the default for a medal nobody is celebrating. */
private val NoCeremony = MedalCeremony(
    animation = Animatable(1f),
    reducedMotion = true,
    plays = mutableIntStateOf(0),
    onPlayed = {},
)

// ─── The drawing ────────────────────────────────────────────────────────────────────────

/**
 * The disc: a rim, alternating facets, and a raised centre for the glyph.
 *
 * The facets are what stop it reading as a coloured circle. Twelve wedges, lit and shaded in turn
 * around the disc, is a struck surface catching light unevenly — the cheapest honest imitation of
 * metal there is, and it survives being drawn at 40dp on a shelf where a gradient would smear into
 * a flat tone.
 */
private fun DrawScope.drawDisc(
    centre: Offset,
    radius: Float,
    palette: MedalPalette,
) {
    drawCircle(color = palette.rim, radius = radius, center = centre)

    val faceRadius = radius * FACE_SHARE
    val sweep = FULL_TURN / FACETS
    repeat(FACETS) { facet ->
        val path = Path().apply {
            moveTo(centre.x, centre.y)
            arcTo(
                rect = Rect(center = centre, radius = faceRadius),
                startAngleDegrees = facet * sweep + FACET_OFFSET_DEGREES,
                sweepAngleDegrees = sweep,
                forceMoveTo = false,
            )
            close()
        }
        drawPath(path = path, color = if (facet % 2 == 0) palette.face else palette.edge)
    }

    // The raised middle the glyph is struck into, bounded by its own ring so the facets stop at
    // an edge rather than running under the icon.
    drawCircle(color = palette.face, radius = radius * CORE_SHARE, center = centre)
    drawCircle(
        color = palette.rim,
        radius = radius * CORE_SHARE,
        center = centre,
        style = Stroke(width = radius * CORE_RING_WIDTH_SHARE),
    )
}

/**
 * The two tails the medal hangs on, drawn behind it.
 *
 * Behind and *below*, rather than a band above the disc: a ribbon over the top would need vertical
 * room a shelf cell does not have, and would put the loudest colour on the screen above every
 * medal rather than under the one it belongs to. Two tones, so the fold has a direction — a
 * single-colour ribbon reads as a paper cut-out.
 */
private fun DrawScope.drawRibbon(
    centre: Offset,
    radius: Float,
    bottom: Float,
    ribbon: Color,
    shade: Color,
) {
    val notch = (bottom - centre.y) * RIBBON_NOTCH_SHARE

    fun tail(
        innerX: Float,
        outerX: Float,
        footInner: Float,
        footOuter: Float,
    ) = Path().apply {
        moveTo(centre.x + outerX * radius, centre.y)
        lineTo(centre.x + innerX * radius, centre.y)
        lineTo(centre.x + footInner * radius, bottom)
        lineTo(centre.x + (footInner + footOuter) / 2f * radius, bottom - notch)
        lineTo(centre.x + footOuter * radius, bottom)
        close()
    }

    val left = tail(
        innerX = -RIBBON_INNER,
        outerX = -RIBBON_OUTER,
        footInner = -RIBBON_FOOT_INNER,
        footOuter = -RIBBON_FOOT_OUTER,
    )
    val right = tail(
        innerX = RIBBON_INNER,
        outerX = RIBBON_OUTER,
        footInner = RIBBON_FOOT_INNER,
        footOuter = RIBBON_FOOT_OUTER,
    )

    drawPath(path = left, color = shade)
    drawPath(path = right, color = ribbon)
}

/**
 * Spokes of light thrown off as the medal lands, fading as they go.
 *
 * They leave from *under* the disc and are gone by the time it has settled, which is what makes
 * them read as light coming off the object rather than as a sunburst it is sitting on.
 */
private fun DrawScope.drawRays(
    centre: Offset,
    radius: Float,
    progress: Float,
    color: Color,
) {
    val local = ((progress - RAY_START) / RAY_SPAN).coerceIn(0f, 1f)
    if (local <= 0f || local >= 1f) return

    val reach = radius * (RAY_NEAR + (RAY_FAR - RAY_NEAR) * DECELERATE.transform(local))
    val alpha = (1f - local) * RAY_ALPHA
    val width = radius * RAY_WIDTH_SHARE * (1f - local)

    repeat(RAYS) { ray ->
        val heading = (ray.toFloat() / RAYS) * TAU
        val from = Offset(
            x = centre.x + cos(heading) * radius * RAY_INNER,
            y = centre.y + sin(heading) * radius * RAY_INNER,
        )
        val to = Offset(x = centre.x + cos(heading) * reach, y = centre.y + sin(heading) * reach)
        drawLine(color = color, start = from, end = to, strokeWidth = width, alpha = alpha)
    }
}

/**
 * The rings that leave the medal at the strike: expanding circles, thinning as they fade.
 *
 * Two, a beat apart. One ring is a ripple and reads as something being *touched*; a second one
 * chasing it reads as something going off, which is the register this animation is in. They are
 * drawn in the metal's own lit tone rather than in its rim, so the event is the medal throwing
 * light rather than a dark outline growing out of it.
 */
private fun DrawScope.drawShockwave(
    centre: Offset,
    radius: Float,
    progress: Float,
    color: Color,
) {
    repeat(WAVES) { wave ->
        val local = ((progress - WAVE_START - wave * WAVE_STAGGER) / WAVE_SPAN).coerceIn(0f, 1f)
        if (local <= 0f || local >= 1f) return@repeat

        val eased = DECELERATE.transform(local)
        drawCircle(
            color = color,
            radius = radius * (WAVE_NEAR + (WAVE_FAR - WAVE_NEAR) * eased),
            center = centre,
            // The trailing ring is fainter, so the two read as one event with a tail rather than
            // as two rings somebody forgot to align.
            alpha = (1f - local) * WAVE_ALPHA * (1f - wave * WAVE_FADE_PER_RING),
            style = Stroke(width = radius * WAVE_WIDTH_SHARE * (1f - eased)),
        )
    }
}

/**
 * A band of light crossing the metal once, clipped to the disc.
 *
 * The last phase, and the one that does the most for how expensive the medal looks: a struck
 * surface catches the light as it turns, and a sweep that arrives *after* the disc has settled is
 * the moment it stops being a drawing of a medal.
 */
private fun DrawScope.drawShine(
    centre: Offset,
    radius: Float,
    progress: Float,
    color: Color,
) {
    val local = ((progress - SHINE_START) / SHINE_SPAN).coerceIn(0f, 1f)
    if (local <= 0f || local >= 1f) return

    val band = radius * SHINE_WIDTH_SHARE
    val travel = radius * (SHINE_FROM + (SHINE_TO - SHINE_FROM) * local)

    clipPath(path = Path().apply { addOval(Rect(center = centre, radius = radius)) }) {
        translate(left = centre.x, top = centre.y) {
            rotate(degrees = SHINE_ANGLE_DEGREES, pivot = Offset.Zero) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, color, Color.Transparent),
                        startX = travel - band,
                        endX = travel + band,
                    ),
                    topLeft = Offset(x = travel - band, y = -radius * SHINE_HEIGHT_SHARE),
                    size = Size(width = band * 2f, height = radius * SHINE_HEIGHT_SHARE * 2f),
                    alpha = (1f - local).coerceAtMost(SHINE_ALPHA_CEILING),
                )
            }
        }
    }
}

/** The metric's mark — the same three-way split the quest board's marks make, for one reason. */
private fun AchievementMetric.medalGlyph(): ImageVector =
    when (this) {
        AchievementMetric.SESSIONS_FINISHED -> Icons.Outlined.FitnessCenter
        AchievementMetric.STREAK_WEEKS -> Icons.Outlined.LocalFireDepartment
        AchievementMetric.RECORDS_BROKEN -> Icons.Outlined.EmojiEvents
    }

/** One metal's four tones, resolved once so the drawing functions take colours and not a tier. */
private data class MedalPalette(
    val face: Color,
    val edge: Color,
    val rim: Color,
    val glyph: Color,
)

private fun TitanMedalColors.of(
    tier: AchievementTier,
    isUnlocked: Boolean,
): MedalPalette =
    when {
        !isUnlocked -> MedalPalette(face = lockedFace, edge = lockedEdge, rim = lockedRim, glyph = lockedGlyph)

        tier == AchievementTier.GOLD ->
            MedalPalette(face = goldFace, edge = goldEdge, rim = goldRim, glyph = goldGlyph)

        tier == AchievementTier.SILVER ->
            MedalPalette(face = silverFace, edge = silverEdge, rim = silverRim, glyph = silverGlyph)

        else -> MedalPalette(face = bronzeFace, edge = bronzeEdge, rim = bronzeRim, glyph = bronzeGlyph)
    }

/** The medal on a shelf: two rows of three fit a 360dp screen with their names under them. */
val ACHIEVEMENT_MEDAL_SIZE: Dp = 84.dp

/** The one medal a screen is about — the newest unlock, or the one the recap just paid for. */
val ACHIEVEMENT_MEDAL_HERO_SIZE: Dp = 128.dp

/** The medal beside a line of text, where it marks a row rather than anchoring a screen. */
val ACHIEVEMENT_MEDAL_COMPACT_SIZE: Dp = 56.dp

/** How long a strike takes, from the disc arriving to the light leaving it. */
private const val STRIKE_MILLIS = 1_100

/** The share of the strike the disc's own arrival takes; the rest is light coming off it. */
private const val POP_SHARE = 0.42f

/** How small the disc starts, and how far it turns on its way in. */
private const val POP_FLOOR = 0.35f
private const val POP_TURN_DEGREES = -38f

/** A back-out curve: past the mark, and settling onto it. The overshoot is the strike. */
private val POP_EASING = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** Distance covered early and barely at all late — what a thrown thing does. */
private val DECELERATE = CubicBezierEasing(0.16f, 0.84f, 0.34f, 1f)

private const val DISC_RADIUS_SHARE = 0.36f
private const val DISC_CENTRE_Y = 0.40f
private const val FACE_SHARE = 0.90f
private const val CORE_SHARE = 0.52f
private const val CORE_RING_WIDTH_SHARE = 0.06f
private const val GLYPH_SHARE = 0.26f

/** Twelve facets: enough to read as struck, few enough to stay distinct at shelf size. */
private const val FACETS = 12
private const val FACET_OFFSET_DEGREES = -90f

private const val RIBBON_INNER = 0.16f
private const val RIBBON_OUTER = 0.74f
private const val RIBBON_FOOT_INNER = 0.30f
private const val RIBBON_FOOT_OUTER = 0.98f
private const val RIBBON_NOTCH_SHARE = 0.30f

private const val RAYS = 10
private const val RAY_START = 0.04f
private const val RAY_SPAN = 0.44f
private const val RAY_INNER = 0.62f
private const val RAY_NEAR = 0.80f
private const val RAY_FAR = 1.55f
private const val RAY_WIDTH_SHARE = 0.10f
private const val RAY_ALPHA = 0.85f

/** Two rings: one ripples, two go off. */
private const val WAVES = 2

/** How far behind the first the second ring leaves, as a share of the strike. */
private const val WAVE_STAGGER = 0.10f

/** How much fainter each trailing ring is drawn. */
private const val WAVE_FADE_PER_RING = 0.45f

private const val WAVE_START = 0.10f
private const val WAVE_SPAN = 0.50f
private const val WAVE_NEAR = 0.60f
private const val WAVE_FAR = 1.75f
private const val WAVE_WIDTH_SHARE = 0.18f
private const val WAVE_ALPHA = 0.7f

private const val SHINE_START = 0.36f
private const val SHINE_SPAN = 0.50f
private const val SHINE_FROM = -1.4f
private const val SHINE_TO = 1.4f
private const val SHINE_WIDTH_SHARE = 0.30f
private const val SHINE_HEIGHT_SHARE = 1.6f
private const val SHINE_ANGLE_DEGREES = 24f
private const val SHINE_ALPHA_CEILING = 0.9f

/** The middle of the box, against which the disc's own centre is offset. */
private const val HALF = 0.5f

private const val FULL_TURN = 360f
private const val TAU = 6.2831855f

// ─── Previews ───────────────────────────────────────────────────────────────────────────

@Preview(name = "Achievement medals")
@Composable
fun AchievementMedalPreview() {
    TitanTheme(theme = ThemePreference.LIGHT) {
        MedalRow()
    }
}

@Preview(name = "Achievement medals - dark")
@Composable
fun AchievementMedalDarkPreview() {
    TitanTheme(theme = ThemePreference.DARK) {
        MedalRow()
    }
}

@Composable
private fun MedalRow() {
    Box(modifier = Modifier.padding(TitanTheme.spacing.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(TitanTheme.spacing.md)) {
            AchievementMedal(
                tier = AchievementTier.GOLD,
                metric = AchievementMetric.SESSIONS_FINISHED,
                isUnlocked = true,
            )
            AchievementMedal(
                tier = AchievementTier.SILVER,
                metric = AchievementMetric.STREAK_WEEKS,
                isUnlocked = true,
            )
            AchievementMedal(
                tier = AchievementTier.BRONZE,
                metric = AchievementMetric.RECORDS_BROKEN,
                isUnlocked = true,
            )
            AchievementMedal(
                tier = AchievementTier.SILVER,
                metric = AchievementMetric.SESSIONS_FINISHED,
                isUnlocked = false,
            )
        }
    }
}
