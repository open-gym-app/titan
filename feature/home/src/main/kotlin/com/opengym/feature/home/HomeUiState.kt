package com.opengym.feature.home

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.ui.component.WeekDay
import kotlin.time.Duration

/**
 * Everything the home screen draws, as one value.
 *
 * ### One nullable value per card, and that is the shape M5 extends
 *
 * Home is a dashboard: a stack of cards, each with its own source, each of which can have
 * nothing to say. So every card is a nullable field here rather than a set of loose figures
 * with flags over them, and **null means the card is not drawn at all** — not drawn empty.
 * That rule is #110's ("a new user should not see a chart of zeros"), applied to the two cards
 * M4 can fill so that the four it adds — the streak, the recent PR, the volume chart, the
 * level and XP — are new fields beside these rather than a second round of plumbing:
 *
 * | #110 adds | Where it lands |
 * | --- | --- |
 * | streak (#34) | a field on [WeekState], beside [WeekState.sessionCount] |
 * | recent PR (#30) | a nullable `recentRecord` here |
 * | eight-week volume (#31) | a nullable `volume` here |
 * | level and XP (#33) | a nullable `level` here |
 *
 * Nothing above is stubbed in advance. A field that is always null until an engine exists is
 * a field every test has to carry and every reader has to ask about; the shape is what has to
 * be right now, and the shape is "one nullable card per source".
 *
 * @param resume the workout the app is holding open, or **null** when there is none — which is
 *   the ordinary case and draws no banner at all.
 * @param week the seven days the user is in the middle of, or **null** for somebody who has
 *   never finished a session. A strip of seven empty squares under a "0 sessions" line is the
 *   empty shell #23 asks for it not to be: it says nothing a first-launch user does not already
 *   know, and it pushes the one thing they need — the way into a workout — down the screen.
 * @param templates the saved templates the quick-start strip offers, already cut to the
 *   handful that fit. Empty draws no strip, which is what a user who has never saved one sees.
 * @param isLoading no read has answered yet. Distinct from every empty state above, all of
 *   which are real answers: flashing "you have never trained" at somebody with four hundred
 *   sessions, for the frame before the query lands, is worse than showing nothing.
 * @param confirmingDiscard the discard confirmation is up — see
 *   [com.opengym.core.ui.component.DiscardSessionDialog].
 * @param isDiscarding the delete is in flight. Holds the dialog's buttons so the workout
 *   cannot be discarded twice.
 * @param message something that happened and needs saying once.
 */
@Immutable
data class HomeUiState(
    val resume: ResumeState? = null,
    val week: WeekState? = null,
    val templates: List<TemplateSummary> = emptyList(),
    val isLoading: Boolean = true,
    val confirmingDiscard: Boolean = false,
    val isDiscarding: Boolean = false,
    val message: HomeMessage? = null,
)

/**
 * The workout the app was left holding, as the banner that offers it back draws it.
 *
 * This is the only route to a session the app still has open: the history lists what has been
 * *finished*, so a workout the process died under is reachable from here and from the
 * new-session flow's refusal (`ResumeSessionDialog`) and nowhere else.
 *
 * @param name what the user called it, or **null** for one started unnamed — the screen falls
 *   back to the date, exactly as the workout's own header does.
 * @param startedAt when the session row was opened. What that fallback is built from.
 * @param elapsed how long it has been running, paused stretches taken out. Recomputed once a
 *   second from the injected clock — see [HomeViewModel].
 * @param isPaused whether it was suspended when the app last wrote its draft. Changes what the
 *   banner is called rather than what it offers: a paused workout and a running one are picked
 *   back up the same way.
 * @param loggedSets how many sets are in it. Not drawn on the banner — the figure that matters
 *   at a glance is the clock — but it is the whole of the discard question, which is a
 *   different question when the answer costs eleven sets.
 * @param position where the user left off, or **null** when there is no draft to say. A draft
 *   is written on every logged set, so null here means a session that was opened and never
 *   worked in, or one whose draft could not be read.
 */
@Immutable
data class ResumeState(
    val sessionId: Long,
    val name: String? = null,
    val startedAt: Long = 0L,
    val elapsed: Duration = Duration.ZERO,
    val isPaused: Boolean = false,
    val loggedSets: Int = 0,
    val position: SessionPosition? = null,
)

/**
 * Where in the workout the user stopped: `Bench press · set 3 of 4`.
 *
 * The exercise is named rather than numbered because it is what a lifter coming back to their
 * phone twenty minutes later is actually trying to remember. It costs one catalogue read for
 * one row — see [HomeViewModel] — which is the cheapest thing on this screen.
 *
 * @param setNumber the set they are on, one-based: the sets already ticked off, plus one,
 *   capped at [setCount] so a finished exercise reads "set 4 of 4" rather than "set 5 of 4".
 * @param setCount how many sets that exercise lays out. **Zero** for an exercise added to the
 *   workout with nothing planned under it yet, which the screen draws as the name alone —
 *   "set 1 of 0" is not a position, it is a bug rendered in words.
 */
@Immutable
data class SessionPosition(
    val exerciseName: String,
    val setNumber: Int,
    val setCount: Int,
)

/**
 * The week the user is in the middle of.
 *
 * The week runs from the locale's own first day, not from Monday — see
 * [com.opengym.core.ui.format.firstDayOfWeek]. It is a *calendar* week rather than the last
 * seven days, because "this week" is a thing a lifter plans against and a rolling window is
 * not: a rolling window's count drops on a Tuesday because of what happened last Wednesday.
 *
 * @param days exactly seven, in the order the strip draws them.
 * @param sessionCount how many sessions were logged across those seven days. The streak that
 *   sits beside it in the wireframe is #110's — it needs #34's engine, which does not exist in
 *   M4 — and lands here as a second field when it does.
 */
@Immutable
data class WeekState(
    val days: List<WeekDay>,
    val sessionCount: Int,
)

/**
 * Something that happened and needs saying once.
 *
 * An enum rather than a string, matching every other screen's: the ViewModel does not reach
 * for a `Context`, and every user-visible word stays in the screen's resources.
 */
enum class HomeMessage {

    /**
     * The workout could not be thrown away, so it is still there.
     *
     * The banner stays up when this happens, which is the point of reporting it: the session
     * is still open, still the only thing that can be started, and resuming it is still the
     * other way out.
     */
    DISCARD_FAILED,
}
