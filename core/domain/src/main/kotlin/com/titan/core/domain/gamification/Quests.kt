package com.titan.core.domain.gamification

import com.titan.core.domain.model.MILLIGRAMS_PER_KILOGRAM
import com.titan.core.domain.model.Quest
import com.titan.core.domain.model.QuestMetric
import com.titan.core.domain.model.QuestPeriod

/**
 * The quests this build ships, and the only place their targets and prices are written down.
 *
 * A list of values, like [Achievements] and for the same reason: everything that evaluates,
 * completes or draws a quest reads [ALL], so a quest added below needs no other edit — no branch,
 * no wiring, no new idempotency argument. The wording is not here; see [Quest].
 *
 * ### The shape: a weekly rhythm with a monthly arc over it
 *
 * Two cadences, because they answer different questions. The **weekly** set is the loop a user
 * actually plays — it resets often enough that a bad week costs nothing, which is the property
 * that makes a habit tracker survivable rather than a ledger of failures. The **monthly** set is
 * the arc: targets nobody clears in one session, worth several levels, so there is something to
 * be part-way through on the days the weekly board is already done.
 *
 * Every metric appears at both cadences. That is deliberate — a lifter who trains four times a
 * week and never breaks a record should still have a board that fills in, and one who trains
 * twice but goes heavy should have theirs fill in too. A board that only rewards frequency tells
 * half the users they are doing it wrong.
 *
 * The weekly session ladder has **two rungs** ([WEEKLY_SESSIONS] and [WEEKLY_SESSIONS_PLUS])
 * rather than one hard target: three sessions is a good week for most people and five is a strong
 * one, and clearing the first while seeing the second half-filled is a better week's end than
 * missing a single target by two. The rungs are separate quests rather than tiers of one because
 * the lower rung's reward should be *kept* when the higher one is missed.
 *
 * ### The prices
 *
 * Read against [XpCalculator]: a typical session pays about 50 (fifteen for finishing, two a
 * working set), a beaten record 10 to 20, a bronze achievement 50, a silver one 100, and a level
 * costs 100 at the start and several hundred by level 10. A weekly quest is therefore worth about
 * a session — enough to be chased, not enough to make the training itself feel like the small half
 * of the reward. A monthly quest is worth a level or more early on, which is the point of a month.
 *
 * The bounds are asserted rather than left to a reading of this paragraph: `QuestsTest` holds
 * weekly rewards above a session and at or below a silver achievement, monthly ones above every
 * weekly rung and worth at least what the third level costs, and every ladder's harder rung above
 * its easier one. A tuning pass that breaks the economy's shape fails there rather than shipping.
 *
 * The volume targets are the one pair not written as the number they compare against: a
 * [QuestMetric.VOLUME_MILLIGRAMS] target is milligrams, and "twenty tonnes" is more legible as
 * [kilograms] of them than as `20_000_000_000`. The conversion is a multiplication in this file
 * and nothing downstream sees a kilogram — see [QuestMetric.VOLUME_MILLIGRAMS] for why the
 * domain does not divide.
 *
 * **These values are starting defaults**, like [Achievements]' thresholds and
 * [StreakCalculator.STREAK_GRACE_HOURS]: nothing here has been validated against real usage. Each
 * is named and read from nowhere else, so retuning one is an edit to a single line, and a test
 * that wants "one short of the target" asks the catalogue rather than restating the number.
 *
 * ### The ids are a storage contract
 *
 * [Quest.id] is half of what a completion row is keyed by — the other half being the period it
 * was completed in — so renaming one does not rename the rows already written under the old
 * spelling. The user's completed quest stops matching any catalogue entry, and the quest becomes
 * completable again in a period they already finished it in, paying its XP twice. `QuestsTest`
 * pins the id set for that reason: a failure there is the reminder that a rename needs a
 * migration, not a bug in the test.
 */
object Quests {

    // ─── Weekly ─────────────────────────────────────────────────────────────────────────────

    /** Train three times this week: the rung a consistent week clears. */
    val WEEKLY_SESSIONS = Quest(
        id = "weekly_sessions",
        metric = QuestMetric.SESSIONS_FINISHED,
        target = 3,
        period = QuestPeriod.WEEKLY,
        xpReward = 40,
    )

    /**
     * Train five times this week: the rung a strong one clears. See the note on [Quests].
     *
     * The dearest weekly quest, and still under a silver achievement, which is the ceiling for
     * anything weekly — a milestone reached once in an install's life should not be outweighed by
     * a board that comes back every Monday.
     */
    val WEEKLY_SESSIONS_PLUS = Quest(
        id = "weekly_sessions_plus",
        metric = QuestMetric.SESSIONS_FINISHED,
        target = 5,
        period = QuestPeriod.WEEKLY,
        xpReward = 70,
    )

    /**
     * Break one record this week.
     *
     * A target of one rather than a count: records are not something a user can decide to produce
     * on schedule, and asking for three in a week would leave the quest permanently unfinished
     * for anyone past their first few months of training.
     */
    val WEEKLY_RECORD = Quest(
        id = "weekly_record",
        metric = QuestMetric.RECORDS_BROKEN,
        target = 1,
        period = QuestPeriod.WEEKLY,
        xpReward = 45,
    )

    /**
     * Move twenty tonnes this week.
     *
     * Roughly three sessions of moderate lifting — deliberately clearable by the same week that
     * clears [WEEKLY_SESSIONS], so the board rewards one good week twice rather than pulling the
     * user in two directions.
     */
    val WEEKLY_VOLUME = Quest(
        id = "weekly_volume",
        metric = QuestMetric.VOLUME_MILLIGRAMS,
        target = kilograms(20_000),
        period = QuestPeriod.WEEKLY,
        xpReward = 40,
    )

    // ─── Monthly ────────────────────────────────────────────────────────────────────────────

    /** Twelve sessions in the month: three a week, held for four weeks. */
    val MONTHLY_SESSIONS = Quest(
        id = "monthly_sessions",
        metric = QuestMetric.SESSIONS_FINISHED,
        target = 12,
        period = QuestPeriod.MONTHLY,
        xpReward = 200,
    )

    /** Five records in the month, across any exercises and any kinds. */
    val MONTHLY_RECORDS = Quest(
        id = "monthly_records",
        metric = QuestMetric.RECORDS_BROKEN,
        target = 5,
        period = QuestPeriod.MONTHLY,
        xpReward = 240,
    )

    /** Eighty tonnes in the month — four weeks of [WEEKLY_VOLUME], near enough. */
    val MONTHLY_VOLUME = Quest(
        id = "monthly_volume",
        metric = QuestMetric.VOLUME_MILLIGRAMS,
        target = kilograms(80_000),
        period = QuestPeriod.MONTHLY,
        xpReward = 220,
    )

    /**
     * Every quest this build knows about, weekly before monthly and cheapest rung of a ladder
     * first.
     *
     * The order is the order a board draws them in and the order completions are reported in, so
     * a user who clears both rungs of the session ladder in one session reads as having earned
     * the smaller one first — the same call [Achievements.ALL] makes.
     */
    val ALL: List<Quest> = listOf(
        WEEKLY_SESSIONS,
        WEEKLY_SESSIONS_PLUS,
        WEEKLY_RECORD,
        WEEKLY_VOLUME,
        MONTHLY_SESSIONS,
        MONTHLY_RECORDS,
        MONTHLY_VOLUME,
    )
}

/**
 * [count] kilograms, as the milligrams a [QuestMetric.VOLUME_MILLIGRAMS] target is stated in.
 *
 * Here rather than at the two call sites so the catalogue keeps reading as the tonnages it
 * describes, and so retuning one is still an edit to a single legible number.
 */
private fun kilograms(count: Int): Long = count * MILLIGRAMS_PER_KILOGRAM.toLong()
