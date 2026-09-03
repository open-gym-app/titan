package com.opengym.core.domain.usecase

import com.opengym.core.domain.gamification.Quests
import com.opengym.core.domain.model.Quest
import com.opengym.core.domain.model.QuestMetric
import com.opengym.core.domain.model.QuestPeriod
import com.opengym.core.domain.model.QuestProgress
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.ProgressionRepository
import com.opengym.core.domain.repository.QuestRepository
import com.opengym.core.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Every quest with how far along it is, for the periods they are currently in.
 *
 * ### It stores nothing, and counts nothing per quest
 *
 * The three things a quest counts — sessions, records, volume — are period aggregates over rows
 * the app already holds, so this derives them on read rather than keeping a counter per quest
 * that a deleted session could leave stale. That is the whole reason [QuestMetric] has three
 * values and not a rule language: a metric here is a promise that the figure behind it is one
 * query.
 *
 * The reads are per **period**, not per quest, so the seven quests in [Quests.ALL] cost six
 * flows rather than seven — and adding an eighth weekly quest costs none at all.
 *
 * ### Scalars, not the weekly series
 *
 * [ProgressionRepository.countSessionsFinished] and [ProgressionRepository.getVolume] rather
 * than the [ProgressionRepository.getWeeklyTraining] and [ProgressionRepository.getWeeklyVolume]
 * the charts read. Summing those would work — the queries behind them filter on the day range
 * and only *group* by week — but it reads a charting API off its grain: they bucket by
 * `TrainingWeek`'s Monday weeks, and a **month is not a whole number of those**. The two scalar
 * reads state the period's figure in one row and are correct over any range by construction.
 *
 * The count is also not the same figure. [com.opengym.core.domain.model.WeeklyTraining] is
 * careful to distinguish sessions from training days, and consistency is measured with the
 * second; a quest to train five times is met by five workouts however they fall across the
 * days, so this counts sessions.
 *
 * ### Completion is read, not derived
 *
 * [QuestProgress.isComplete] comes from [QuestRepository], never from `progress >= target`. A
 * user who deletes a session they had already been paid for keeps the quest — see
 * [QuestProgress], which carries the argument.
 */
class QuestProgressUseCase(
    private val progressionRepository: ProgressionRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val questRepository: QuestRepository,
    private val clock: Clock = Clock.SYSTEM,
    private val catalogue: List<Quest> = Quests.ALL,
) {

    /**
     * The board, in [catalogue] order, recomputed whenever any figure behind it moves.
     *
     * Today is read once, here, rather than per emission — and the flows behind it are built
     * against that date — so a board left open across midnight keeps reporting the period it
     * was built in until something calls this again. That is the same staleness every dated
     * screen in the app has and is deliberately not solved with a ticker here: the correction
     * arrives with the next finished session, which is the only event that could have changed
     * the answer anyway.
     */
    operator fun invoke(): Flow<List<QuestProgress>> = at(today())

    /**
     * The same board, for the periods **[date]** falls in.
     *
     * For the completion pass, which must credit the period the *workout* happened in rather
     * than the one the cascade is running in. A session finished at 23:50 UTC on a Sunday and
     * processed after a crash on the Monday belongs to the week it was trained in — the same
     * rule a record's `achievedAt` and an unlock's `unlockedAt` follow, and the one place
     * reading a clock here would quietly pay a quest out of the wrong week.
     */
    fun at(date: LocalDate): Flow<List<QuestProgress>> {
        val perPeriod = catalogue.groupBy(Quest::period).map { (period, quests) ->
            progressFor(period = period, quests = quests, today = date)
        }

        // `combine` over an empty list never emits, which would leave a board that has no
        // quests loading forever rather than showing it is empty.
        if (perPeriod.isEmpty()) return flowOf(emptyList())

        return combine(perPeriod) { rows -> rows.flatMap { it } }
            .map { rows -> rows.sortedBy { row -> catalogue.indexOf(row.quest) } }
    }

    /** Every quest of one [period], sharing that period's three reads. */
    private fun progressFor(
        period: QuestPeriod,
        quests: List<Quest>,
        today: LocalDate,
    ): Flow<List<QuestProgress>> {
        val start = period.startOf(today)
        val days = start..period.endOf(today)

        return combine(
            sessionsIn(days),
            recordsIn(days),
            volumeIn(days),
            questRepository.observeCompletedIds(start),
        ) { sessions, records, volume, completed ->
            quests.map { quest ->
                QuestProgress(
                    quest = quest,
                    // Widened rather than narrowed: a count fits a `Long`, and a period's
                    // volume in milligrams does not fit an `Int`.
                    progress = when (quest.metric) {
                        QuestMetric.SESSIONS_FINISHED -> sessions.toLong()
                        QuestMetric.RECORDS_BROKEN -> records.toLong()
                        QuestMetric.VOLUME_MILLIGRAMS -> volume
                    },
                    isComplete = quest.id in completed,
                    periodStart = start,
                )
            }
        }
    }

    private fun sessionsIn(days: ClosedRange<LocalDate>): Flow<Int> = progressionRepository.countSessionsFinished(days)

    private fun recordsIn(days: ClosedRange<LocalDate>): Flow<Int> =
        personalRecordRepository
            .getRecordTally(days)
            .map { tally -> tally.records }

    /**
     * The period's volume, in the milligram-reps the store sums and nothing else.
     *
     * Passed through undivided, which is the point: converting to a display unit here would
     * decide for [com.opengym.core.domain.model.UnitSystem], and rounding to one would let
     * 19,999.6 kg either miss the target or clear it a fraction of a rep early depending on
     * which way it went. Compared exactly, and converted once, at the screen — see
     * [QuestMetric.VOLUME_MILLIGRAMS].
     */
    private fun volumeIn(days: ClosedRange<LocalDate>): Flow<Long> = progressionRepository.getVolume(days)

    /**
     * Today, in **UTC** — the zone every period boundary in this app is cut in.
     *
     * Not the device's zone, deliberately: see [QuestPeriod], which carries the argument and
     * the approximation it accepts.
     */
    private fun today(): LocalDate = Instant.ofEpochMilli(clock.nowMillis()).atZone(ZoneOffset.UTC).toLocalDate()
}
