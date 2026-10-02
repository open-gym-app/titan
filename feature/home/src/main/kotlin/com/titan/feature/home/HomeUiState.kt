package com.titan.feature.home

import androidx.compose.runtime.Immutable
import com.titan.core.domain.model.PersonalRecordEntry
import com.titan.core.domain.model.QuestPeriod
import com.titan.core.domain.model.QuestProgress
import com.titan.core.domain.model.TemplateSummary
import com.titan.core.domain.model.WeeklyVolume
import com.titan.core.ui.component.WeekDay
import kotlin.time.Duration

/**
 * Everything the home screen draws, as one value.
 *
 * ### One empty value per card
 *
 * Home is a dashboard: a stack of cards, each with its own source, each of which can have
 * nothing to say. So every card is a field of its own here rather than a set of loose figures
 * with flags over them, and **a source with nothing to say draws no card at all** — not an
 * empty one. That is #110's rule ("a new user should not see a chart of zeros"), and it is
 * why a brand-new user gets the quick-start actions and nothing else.
 *
 * #23 shaped this for the four sources M5 has since built, and they landed where it said they
 * would — one field per source, no second round of plumbing:
 *
 * | #110 added | Where it landed |
 * | --- | --- |
 * | streak (#34) | [WeekState.streak], beside [WeekState.sessionCount] |
 * | recent PR (#30) | [recentRecord] |
 * | eight-week volume (#31) | [volume] |
 * | level and XP (#33) | [level] |
 *
 * Four of the five are nullable and the fifth is a list, which is the same statement: a
 * chart is a series, and an empty series is exactly "nothing to draw" without a second way of
 * saying so — the shape [templates] already had. [quests] is the newest of them, and it lands
 * in exactly that shape.
 *
 * Nothing above is stubbed in advance. A field that is always null until an engine exists is
 * a field every test has to carry and every reader has to ask about; the shape is what has to
 * be right now, and the shape is "one nullable card per source".
 *
 * @param level the standing the header draws — the level, and how far into it the user is — or
 *   **null** for somebody who has never banked a point, whose header would otherwise read
 *   `LVL 1 · 0 / 100 XP` before they have done anything at all.
 * @param resume the workout the app is holding open, or **null** when there is none — which is
 *   the ordinary case and draws no banner at all.
 * @param week the seven days the user is in the middle of, or **null** for somebody who has
 *   never finished a session. A strip of seven empty squares under a "0 sessions" line is the
 *   empty shell #23 asks for it not to be: it says nothing a first-launch user does not already
 *   know, and it pushes the one thing they need — the way into a workout — down the screen.
 * @param quests what is left to clear this week, or **null** for a user with nothing to show
 *   yet — see [QuestSummary], which is absent rather than zeroed for the same reason [week] is.
 * @param templates the saved templates the quick-start strip offers, already ordered and cut
 *   to the handful that fit — most recently trained from first, see [HomeViewModel]. Empty
 *   draws no strip, which is what a user who has never saved one sees.
 * @param recentRecord the last personal record the user broke, with what it beat, or **null**
 *   for somebody who has broken none. Carried as the domain's own row rather than reduced to
 *   strings here: every figure on it is formatted against the ambient unit system, which a
 *   ViewModel cannot see — see CONTRIBUTING.md § Using the design system.
 * @param volume the last eight weeks of training volume, one bucket per week, untrained weeks
 *   included as zeroes so a rest week is a gap in the bars rather than a gap in the series.
 *   **Empty** when the eight weeks hold no training at all, which draws no card — for the new
 *   user and for the lapsed one alike, neither of whom is told anything by eight noughts.
 * @param isLoading no read has answered yet. Distinct from every empty state above, all of
 *   which are real answers: flashing "you have never trained" at somebody with four hundred
 *   sessions, for the frame before the query lands, is worse than showing nothing.
 * @param confirmingDiscard the discard confirmation is up — see
 *   [com.titan.core.ui.component.DiscardSessionDialog].
 * @param isDiscarding the delete is in flight. Holds the dialog's buttons so the workout
 *   cannot be discarded twice.
 * @param message something that happened and needs saying once.
 */
@Immutable
data class HomeUiState(
    val level: LevelState? = null,
    val resume: ResumeState? = null,
    val week: WeekState? = null,
    val quests: QuestSummary? = null,
    val templates: List<TemplateSummary> = emptyList(),
    val recentRecord: PersonalRecordEntry? = null,
    val volume: List<WeeklyVolume> = emptyList(),
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
 * @param loggedSets how many *ticked* sets are in it, the same figure the workout screen puts
 *   in this question. Not drawn on the banner — the figure that matters
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
 * Where in the workout the user stopped: `Bench press · set 3`.
 *
 * The exercise is named rather than numbered because it is what a lifter coming back to their
 * phone twenty minutes later is actually trying to remember. It costs one catalogue read for
 * one row — see [HomeViewModel] — which is the cheapest thing on this screen.
 *
 * ### No "of N", because there is no N
 *
 * A workout takes sets until the user stops taking them: nothing in the app records how many
 * an exercise was *meant* to get, and the session screen itself counts what happened — "3
 * sets" — rather than progress against a plan. So this is the set they came back to do, on its
 * own. See [HomeViewModel.position] for what the draft can and cannot be asked.
 *
 * @param setNumber the set they are on, one-based: the sets already ticked off on this
 *   exercise, plus one. **1** for an exercise they have not started, which is the truth — the
 *   next thing they do on it is its first set.
 */
@Immutable
data class SessionPosition(
    val exerciseName: String,
    val setNumber: Int,
)

/**
 * The week the user is in the middle of.
 *
 * The week runs from the locale's own first day, not from Monday — see
 * [com.titan.core.ui.format.firstDayOfWeek]. It is a *calendar* week rather than the last
 * seven days, because "this week" is a thing a lifter plans against and a rolling window is
 * not: a rolling window's count drops on a Tuesday because of what happened last Wednesday.
 *
 * @param days exactly seven, in the order the strip draws them.
 * @param sessionCount how many sessions were logged across those seven days.
 * @param streak consecutive days trained, or **null** when there is no streak to report. Null
 *   covers both a user who has never had one and a user whose last one has since lapsed: a
 *   stored streak is only recomputed when a session finishes, so the figure on the row says
 *   twelve for as long as nobody trains, and drawing it unasked would tell somebody who last
 *   trained in March that they are twelve days deep. Whether it is still running is
 *   [com.titan.core.domain.gamification.StreakCalculator.isLive]'s answer, taken when the
 *   screen is assembled — see [HomeViewModel].
 */
@Immutable
data class WeekState(
    val days: List<WeekDay>,
    val sessionCount: Int,
    val streak: Int? = null,
)

/**
 * The standing the header draws: which level, and how far into it.
 *
 * ### Points into the level, not the lifetime total
 *
 * The header is one line and a bar, and the bar is a *position* — the figure beside it has to
 * be the one it is drawing, or the two disagree in the only place they are read together. The
 * lifetime total is the quest log's (#104), where there is room to say what it bought.
 *
 * The same three figures the just-finished recap's `LevelStanding` draws, in the same order,
 * so a user who watches a level tick over on the summary and then opens Home is reading one
 * design rather than two.
 *
 * @param level the level the user is on.
 * @param xpIntoLevel points banked toward the next one, `0..xpForLevel`.
 * @param xpForLevel what a level costs — [com.titan.core.domain.gamification.XpCalculator]'s
 *   `XP_PER_LEVEL`, carried rather than read at the call site so the bar and the figure beside
 *   it are scaled by the same number.
 */
@Immutable
data class LevelState(
    val level: Int,
    val xpIntoLevel: Int,
    val xpForLevel: Int,
) {

    /**
     * How far through the level, `0f..1f` — what the bar is drawn from.
     *
     * Guarded against a zero cost rather than trusting the curve: `XP_PER_LEVEL` is a tuning
     * constant, and a divide by zero here would take out the whole screen for a value somebody
     * is entitled to change.
     */
    val progress: Float
        get() = if (xpForLevel <= 0) 0f else (xpIntoLevel.toFloat() / xpForLevel).coerceIn(0f, 1f)
}

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

/**
 * The quest board as the dashboard shows it: this week's score, and the quests closest to done.
 *
 * ### Why the dashboard carries quests at all
 *
 * The board lived on one screen two taps away, which meant a lifter only found out what was left
 * this week if they went looking. A quest is a thing with a deadline — the one kind of goal that
 * is worthless once you have stopped noticing it — so it belongs where the app is opened. What is
 * *not* here is the whole board: this is a prompt and a way through, and the seven-row version
 * with its cadences and cleared group is still the quest log's job.
 *
 * ### Absent, not zeroed
 *
 * Built as null for a user who has never moved a quest — see [HomeViewModel]. That is #110's rule
 * ("a new user should not see a chart of zeros") applied to a board rather than a chart, and it is
 * the same call [WeekState] makes: a strip of untouched targets over a "0 / 4" tells somebody who
 * has never trained nothing they do not know, and pushes the one thing they need — the way into a
 * workout — down the screen.
 *
 * @param clearedThisPeriod how many of [totalThisPeriod] are banked, in the cadence [period]
 *   names. Weekly, in every build that ships the current catalogue: the near deadline is the one
 *   worth a dashboard, and a monthly score moves too slowly to be worth the space.
 * @param totalThisPeriod how many quests that cadence has.
 * @param period which cadence the score is about.
 * @param next the quests still open, **nearest to done first**, cut to the handful that fit.
 *   Ordered by how close they are rather than by the catalogue, because the question a dashboard
 *   answers is "what could I finish today" — and a quest at 2 / 3 is a different invitation from
 *   one at 0 / 20,000. Empty when the cadence is cleared, which the card draws as its own state.
 */
@Immutable
data class QuestSummary(
    val clearedThisPeriod: Int,
    val totalThisPeriod: Int,
    val period: QuestPeriod,
    val next: List<QuestProgress>,
) {

    /** Whether every quest of [period] is banked — the card's one celebratory state. */
    val isCleared: Boolean get() = clearedThisPeriod == totalThisPeriod
}
