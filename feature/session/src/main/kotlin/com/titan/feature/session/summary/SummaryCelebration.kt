package com.titan.feature.session.summary

import androidx.compose.runtime.Immutable
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.QuestPeriod
import com.titan.core.domain.model.SessionProgression
import com.titan.core.domain.model.XpEvent

/**
 * What the just-finished recap celebrates: the points, where they came from, and what they
 * moved.
 *
 * Derived from one `SessionProgression` — see [from] — and never re-queried. The cascade's
 * value is the only trustworthy account of what the finish earned, for the reason
 * `SessionProgression` gives: each figure was produced inside the write that persisted it.
 *
 * ### Absent, not empty
 *
 * A session that earned nothing produces **no** celebration rather than one reading zero, and
 * the screen then draws no section at all. `0 XP` under a heading that says "earned" is the app
 * telling a user who just trained that it did not count — and the case is not hypothetical: a
 * re-run of an already-processed session reports nothing, by design. See [from].
 *
 * @property xpAwarded the points banked across every award, session events and the ones the
 *   achievement unlocks paid alike.
 * @property sources the breakdown, ordered as [XpSourceKind] declares. #111 asks for the
 *   sources to be visible rather than a single opaque number: a lifter who sees `+50 · record`
 *   learns what the app pays for, and one who sees `+180` learns nothing.
 * @property levelBefore the level held when the session started. Equal to [level] when nothing
 *   was crossed; below it by one per threshold passed, which is what the bar sweeps through.
 * @property levelProgressBefore how far into [levelBefore] the user stood before the session,
 *   `0f..1f` — where the sweep starts from.
 * @property level the level held after the session.
 * @property leveledUp whether the session crossed a boundary. Taken from the awards rather than
 *   compared between two levels, because the awards are where the crossing was observed.
 * @property levelProgress how far into [level] the user now stands, `0f..1f`.
 * @property xpIntoLevel points banked toward the next level.
 * @property xpForLevel what [level] costs on its own, so the screen's "43 / 170" needs no
 *   second import of the curve. Levels cost more as they go, so a sweep through several draws
 *   each one against its own width — see [xpForLevel] with an argument.
 * @property achievements what unlocked, in the order the cascade unlocked them.
 * @property quests what was cleared, in the order the cascade banked them. Named rather than
 *   left as a line of the breakdown for the reason [SummaryQuestsCard] gives: a total says what
 *   the quests paid, and the thing a user wants to know is *which* one they finished.
 */
@Immutable
data class SummaryCelebration(
    val xpAwarded: Int,
    val sources: List<XpSource>,
    val levelBefore: Int,
    val levelProgressBefore: Float,
    val level: Int,
    val leveledUp: Boolean,
    val levelProgress: Float,
    val xpIntoLevel: Int,
    val xpForLevel: Int,
    val achievements: List<SummaryAchievement>,
    val quests: List<SummaryQuest>,
) {

    /**
     * What [level] costs on its own — the denominator while the sweep counts through it.
     *
     * The level the session ended on answers from [xpForLevel], so the last frame of the sweep
     * draws exactly the figure this celebration carries; any level passed on the way answers
     * from the curve.
     */
    fun xpForLevel(level: Int): Int = if (level == this.level) xpForLevel else XpCalculator.xpToNextLevel(level)

    companion object {

        /**
         * [progression] as something to draw, or **null** when there is nothing to celebrate.
         *
         * Null on two inputs that are the same to a reader and different to the engine: a
         * cascade that ran and moved nothing, and a re-run of a session already processed. The
         * screen treats them alike because the user's answer is the same — nothing happened
         * here — and distinguishing them would put a diagnostic on a celebration.
         *
         * An unlocked achievement with no points is still worth a section, which is why the
         * test is on the awards *and* the unlocks rather than on the XP alone. A cleared quest
         * is not in that test: a quest always pays, so a session that cleared one has awards.
         */
        fun from(progression: SessionProgression): SummaryCelebration? {
            val awarded = progression.xpAwarded
            if (awarded <= 0 && progression.achievementsUnlocked.isEmpty()) return null

            // The level after the last award. Falling back to the curve's own first level
            // rather than to zero: a progression carrying unlocks but no awards has no level
            // on it, and level 1 is where such a user stands.
            val level = progression.levelAfter ?: XpCalculator.FIRST_LEVEL
            val total = progression.totalXp ?: XpCalculator.xpRequiredForLevel(level)
            val into = (total - XpCalculator.xpRequiredForLevel(level)).toInt()

            // Where the sweep starts: the level held before the first award, and how far into it
            // the user stood. Derived by subtracting what this session paid from the total it
            // ended on, rather than read from a store — the awards are the only account of the
            // run, and a re-read could have moved on. Coerced because a progression carrying
            // unlocks but no awards has no `levelBefore` to speak of.
            val before = progression.levelBefore ?: level
            val totalBefore = (total - awarded).coerceAtLeast(XpCalculator.xpRequiredForLevel(before))
            val intoBefore = (totalBefore - XpCalculator.xpRequiredForLevel(before)).toInt()

            return SummaryCelebration(
                xpAwarded = awarded,
                sources = sourcesOf(progression),
                levelBefore = before,
                levelProgressBefore = intoBefore.toFloat() / XpCalculator.xpToNextLevel(before),
                level = level,
                leveledUp = progression.leveledUp,
                levelProgress = into.toFloat() / XpCalculator.xpToNextLevel(level),
                xpIntoLevel = into,
                xpForLevel = XpCalculator.xpToNextLevel(level),
                achievements = progression.achievementsUnlocked.map {
                    SummaryAchievement(id = it.achievement.id, xp = it.xp.awarded)
                },
                quests = progression.questsCompleted.map {
                    SummaryQuest(
                        id = it.quest.id,
                        period = it.quest.period,
                        xp = it.xp.awarded,
                    )
                },
            )
        }

        /**
         * The awards folded into one line per kind.
         *
         * Grouped rather than listed one award per row: a session of thirty sets earns thirty
         * identical grants, and a breakdown that printed each of them would bury the record
         * that earned fifty points. The count travels with the line so `30 sets · +150` says
         * both what was done and what it paid.
         *
         * Kinds that earned nothing are dropped, so the list is what happened rather than a
         * fixed four rows with zeroes in them.
         */
        private fun sourcesOf(progression: SessionProgression): List<XpSource> =
            progression.xp
                .groupBy { grant -> grant.event.sourceKind() }
                .map { (kind, grants) ->
                    XpSource(
                        kind = kind,
                        count = grants.size,
                        xp = grants.sumOf { it.award.awarded },
                    )
                }
                .filter { it.xp > 0 }
                .sortedBy { it.kind.ordinal }

        /** Which line of the breakdown an event belongs on. */
        private fun XpEvent.sourceKind(): XpSourceKind =
            when (this) {
                XpEvent.SetLogged -> XpSourceKind.SETS
                XpEvent.SessionFinished -> XpSourceKind.SESSION
                is XpEvent.PrBroken -> XpSourceKind.RECORDS
                is XpEvent.AchievementUnlocked -> XpSourceKind.ACHIEVEMENTS
                is XpEvent.QuestCompleted -> XpSourceKind.QUESTS
            }
    }
}

/**
 * One line of the XP breakdown.
 *
 * @property kind what earned it.
 * @property count how many of that thing happened — sets logged, records broken. Always at
 *   least one, since a kind with nothing in it is not in the list.
 * @property xp what they paid together.
 */
@Immutable
data class XpSource(
    val kind: XpSourceKind,
    val count: Int,
    val xp: Int,
)

/**
 * The five things a session can be paid for, in the order the breakdown lists them.
 *
 * Declaration order is display order, running from the routine to the rare: sets are what every
 * session has, an achievement is what a handful of sessions in a lifetime have. A breakdown that
 * sorted by amount would move the rows around between sessions and stop being scannable.
 */
enum class XpSourceKind {

    /** One per completed working set. */
    SETS,

    /** The flat award for finishing at all. */
    SESSION,

    /** One per personal record beaten. */
    RECORDS,

    /** One per milestone unlocked — the points the unlock itself paid. */
    ACHIEVEMENTS,

    /**
     * One per quest completed — the reward the quest itself named.
     *
     * Last, so the line reads after the milestones it sits beside. Unlike every other kind
     * these do not all pay the same: a quest carries its own price, so the line's total is a
     * sum of different rewards rather than a count times a constant.
     */
    QUESTS,
}

/**
 * A milestone unlocked by this session.
 *
 * The id rather than a resolved name: the catalogue is `Achievements.ALL` in the domain and the
 * words are string resources in this module, so the mapping belongs at the composable that has a
 * `Context`. Carrying a resolved string here would put user-visible words in the ViewModel.
 *
 * @property id the achievement's stable id, e.g. `first_session`.
 * @property xp what unlocking it paid.
 */
@Immutable
data class SummaryAchievement(
    val id: String,
    val xp: Int,
)

/**
 * A quest this session cleared.
 *
 * The id rather than a resolved name, and the *period* rather than a resolved deadline, for the
 * reason [SummaryAchievement] gives: the catalogue is `Quests.ALL` in the domain and the words
 * are string resources in this module, so the mapping belongs at the composable that has a
 * `Context`.
 *
 * The period travels because "this week" and "this month" are not the same news. Clearing a
 * monthly quest is the rarer event and the row says so, which a row carrying only a name and a
 * number could not.
 *
 * @property id the quest's stable id, e.g. `weekly_sessions`.
 * @property period the cadence it belongs to.
 * @property xp what clearing it paid. Unlike every other award in the app this is the quest's
 *   own price rather than a constant, which is why it is carried rather than looked up.
 */
@Immutable
data class SummaryQuest(
    val id: String,
    val period: QuestPeriod,
    val xp: Int,
)
