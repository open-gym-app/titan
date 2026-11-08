package com.titan.core.domain.model

import com.titan.core.domain.stats.TrainingWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * A time-boxed goal that recurs: train four times this week, break a record this month.
 *
 * ### Not an achievement
 *
 * [Achievement] is a one-shot permanent unlock — you hold "first personal record" forever. A
 * quest **resets**: it has a deadline, it tracks partial progress, and it can be completed again
 * next period. That is why they are separate models rather than one with a nullable period, and
 * why completion is stored per *period* rather than as a single row.
 *
 * ### The wording is not here
 *
 * A quest carries what it *measures*, not what it says. `:core:domain` is a plain Kotlin library
 * with no `Context`, so a description on this class would make the app untranslatable at the one
 * place a translation is most obviously needed — the same call [Achievement] makes. The screen
 * resolves [id] to a string resource.
 *
 * @property id the stable id, used as the string-resource key and as half of the completion
 *   record's identity.
 * @property metric what is counted, and in what unit — a plain count for two of them and
 *   milligrams for the third.
 * @property target how much of [metric], **in [metric]'s own unit**, completes the quest.
 *   Compared with `>=`, so a lifter who overshoots is done rather than stuck at 5/4. A `Long`
 *   because a volume target is milligrams and twenty tonnes of them overflows an `Int`.
 * @property period how often it resets.
 * @property xpReward what completing it pays, once per period.
 */
data class Quest(
    val id: String,
    val metric: QuestMetric,
    val target: Long,
    val period: QuestPeriod,
    val xpReward: Int,
) {

    init {
        require(target >= 1) { "a quest target must be at least 1, was $target for $id" }
        require(xpReward >= 0) { "a quest reward cannot be negative, was $xpReward for $id" }
    }

    /** Whether [progress], in [metric]'s unit, has finished this quest. */
    fun isMetBy(progress: Long): Boolean = progress >= target
}

/**
 * What a quest counts, and the unit [Quest.target] and `QuestProgress.progress` are in.
 *
 * Three, deliberately, and each one is a figure the app **already** aggregates — see
 * `QuestProgressUseCase`, which reads existing queries rather than adding one per quest. A
 * general rule engine is the thing this is not: #105 asks for a small set, and a fourth metric
 * should be added here when a fourth quest needs it rather than in anticipation.
 *
 * Each entry names its unit, because the unit is not the same for all three and a screen
 * drawing `progress / target` has to know which it is holding.
 */
enum class QuestMetric {

    /** Sessions finished in the period. The count of workouts, not of days. */
    SESSIONS_FINISHED,

    /** Personal records broken in the period, across every exercise and kind. */
    RECORDS_BROKEN,

    /**
     * Volume lifted in the period, in the **milligrams** every weight in this app is stored in.
     *
     * Not kilograms, though a target reads better as one. [UnitSystem] exists, and the unit a
     * quest is *shown* in is the user's, not the catalogue's: an imperial user has to read
     * `24,251 / 44,092 lb`, with **both** numbers converted, and a domain that pre-divided by a
     * kilogram would hand the screen a figure it could only suffix with `lb` or convert twice.
     * Milligrams keep the choice where [UnitSystem] says it belongs — `:core:ui`'s
     * `formatVolume`, which every other volume on every other screen already goes through.
     *
     * The catalogue keeps its legible targets regardless; it writes `kilograms(20_000)` rather
     * than a fourteen-digit constant. See [com.titan.core.domain.gamification.Quests].
     */
    VOLUME_MILLIGRAMS,
}

/**
 * How often a quest resets, and where its boundary falls.
 *
 * ### The boundary is UTC, like every other bucket in this app
 *
 * #105 asks for local midnight. This follows `TrainingWeek` and `StreakCalculator` instead,
 * which both cut their weeks in **UTC** and both document why: the rows carry epoch millis, no
 * table stores the zone a session was logged in, and correcting it means a zone in every query.
 *
 * Consistency is the argument. A weekly quest rolling over at local midnight while the streak
 * and the weekly volume chart roll over at UTC midnight would make "this week" mean two
 * different things on two screens of one app — which is worse than the approximation both of
 * them already accept and write down.
 *
 * The consequence worth naming: there is no daylight-saving transition to get wrong, because
 * UTC has none. What can still move under a user is the *device's* zone, and that changes
 * nothing here — the boundary is computed from an instant, not from the phone's calendar.
 */
enum class QuestPeriod {

    /** Monday to Sunday, the week `TrainingWeek` already defines for every other aggregate. */
    WEEKLY {
        override fun startOf(date: LocalDate): LocalDate = TrainingWeek.startOf(date)

        override fun endOf(date: LocalDate): LocalDate = startOf(date).plusDays(TrainingWeek.DAYS - 1)
    },

    /** The calendar month, first to last. */
    MONTHLY {
        override fun startOf(date: LocalDate): LocalDate = date.withDayOfMonth(1)

        override fun endOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.lastDayOfMonth())
    },
    ;

    /** The first day of the period [date] falls in. */
    abstract fun startOf(date: LocalDate): LocalDate

    /** The last day of it, inclusive. */
    abstract fun endOf(date: LocalDate): LocalDate

    /**
     * The whole period [date] falls in, as the inclusive day range every aggregate read takes.
     *
     * Two calls rather than one so the ends can be reasoned about separately — and so a period
     * whose end is computed from its own start, as the week's is, cannot drift from it.
     */
    fun rangeOf(date: LocalDate): ClosedRange<LocalDate> = startOf(date)..endOf(date)
}
