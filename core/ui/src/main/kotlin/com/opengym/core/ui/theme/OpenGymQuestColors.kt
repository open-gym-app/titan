package com.opengym.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The tinted grounds a quest's mark is drawn on, one per metric, reached through
 * [OpenGymTheme.quest].
 *
 * ### Three hues, and not one of them is new
 *
 * [OpenGymPlateColors] argues that a screen with four saturated hues has no accent left, and
 * [OpenGymCelebrationColors] carves out the one exception: a surface that *is* the content
 * rather than a signal to be scanned between sets. The quest board is the second such surface,
 * and it is held to a stricter rule than the celebration's — every tone below is one of the
 * hues [OpenGymCelebrationColors.confetti] already cycles through, at container strength.
 *
 * So no hue enters the app here. What enters is a *use*: three marks that tell a lifter which
 * kind of thing a quest counts before they have read its name, which is what makes a board of
 * seven scannable rather than a list of sentences. Colour is doing the same job the plate ramp's
 * does — encoding a category — under the same obligation, which is that it never carries the
 * category *alone*: each mark has its own glyph, and each row its own words.
 *
 * ### The accent stays out of it
 *
 * None of these is `primary`. The accent on this screen marks progress and reward — the bar and
 * the XP — and a mark painted in it would compete with the two figures the row is about. A
 * cleared quest drops its tint entirely and goes neutral; see `QuestMark`.
 *
 * @property sessions the ground for a quest counting workouts finished. The teal from the
 *   confetti set: the most ordinary of the three metrics, in the quietest of the three hues.
 * @property onSessions the glyph drawn on [sessions].
 * @property records the ground for a quest counting personal records. Gold, which is what a
 *   record is worth being marked in.
 * @property onRecords the glyph drawn on [records].
 * @property volume the ground for a quest counting kilograms moved. The celebration's violet,
 *   at container strength.
 * @property onVolume the glyph drawn on [volume].
 */
@Immutable
data class OpenGymQuestColors(
    val sessions: Color,
    val onSessions: Color,
    val records: Color,
    val onRecords: Color,
    val volume: Color,
    val onVolume: Color,
)

/*
 * The light set: pale grounds carrying a deep glyph, in the manner of `primaryContainer` and
 * `onPrimaryContainer`, so a mark sits on a card without becoming the loudest thing on it.
 */
internal val OpenGymLightQuestColors = OpenGymQuestColors(
    sessions = Color(0xFFDDEFE9),
    onSessions = Color(0xFF12503F),
    records = Color(0xFFF8EACC),
    onRecords = Color(0xFF6A4703),
    volume = Color(0xFFE6DEF3),
    onVolume = Color(0xFF3A1F63),
)

/*
 * The dark set, inverted in role rather than in value: the ground drops to a deep tint of the
 * same hue and the glyph lifts to the bright end of it — the treatment `achievement` and
 * `onAchievement` already take across the two celebration sets, for the same reason. A pale
 * ground kept from the light set would put three bright squares down the left edge of a
 * near-black screen, which is a row of lamps rather than a set of marks.
 *
 * `volume` is the one tone here that is *lighter* than a mechanical tint of its hue would be.
 * The violet the light set uses drops to a near-black on this scheme and disappears into the
 * card it sits on — 1.11:1, under the floor `ColorSchemeContrastTest` holds these grounds to.
 * Lifted until the tile reads as a shape again, which is a limit worth naming rather than
 * rediscovering.
 */
internal val OpenGymDarkQuestColors = OpenGymQuestColors(
    sessions = Color(0xFF0F332A),
    onSessions = Color(0xFF6FD3B8),
    records = Color(0xFF382A08),
    onRecords = Color(0xFFF2C14E),
    volume = Color(0xFF342553),
    onVolume = Color(0xFFB9A2E4),
)
