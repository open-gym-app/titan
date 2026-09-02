package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.ExerciseAttempt
import com.opengym.core.domain.model.ExerciseEstimate
import com.opengym.core.domain.model.ExerciseProgression
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import com.opengym.core.domain.model.bestByType
import com.opengym.core.domain.pr.OneRepMaxCalculator
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.ProgressionRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * One exercise's progression: what it has been doing over a window, and what its bests are.
 *
 * The per-exercise drill-down's single read. Four sources go in — the window's attempts, the
 * all-time set count, the exercise's records, and the one-rep-max formula the user picked —
 * and one [ExerciseProgression] comes out, so the screen never draws a set of cards assembled
 * from two different moments.
 *
 * ### Which figures the window applies to, and which it does not
 *
 * The **series** are the window's; the **records** and the set count are all-time. A card
 * headed `1RM (EST)` that dropped to a smaller number because the user narrowed the timeframe
 * would be reporting a record they have not lost — a timeframe is a way of looking at a trend,
 * not a claim about what has ever happened.
 *
 * ### Best-of-a-kind comes off the ordering, not off a comparison
 *
 * [PersonalRecordRepository.getRecordsForExercise] returns every record newest first, and a
 * record of a given kind is only ever written when it beats the one before it — so the first
 * row of a type *is* that type's best, and re-deriving "better" here would be a second copy of
 * [PrDetectionUseCase]'s comparison rule, with nothing keeping the two in step.
 *
 * ### What makes a record "new"
 *
 * That it was achieved in the most recent attempt on screen. A record carries its own set's
 * timestamp rather than the session's (see [PrDetectionUseCase]), so the test is against the
 * attempt's **first** set rather than against when the session was closed out — everything the
 * attempt achieved happened at or after that instant, and the attempt before it ended before
 * it. A window with no attempts in it badges nothing, which is the honest reading: nothing was
 * broken in a period nothing happened in.
 *
 * ### The line follows the top set
 *
 * Deliberately not the best projection across every set of a session, which is what the engine
 * records — see [ExerciseEstimate], which explains why a trend and a record are different
 * questions. The arithmetic is [OneRepMaxCalculator]'s either way, so the line and the card
 * above it are two readings of one formula rather than two formulas.
 */
class ExerciseProgressionUseCase(
    private val progressionRepository: ProgressionRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {

    /**
     * [exerciseId]'s progression over [period].
     *
     * @param period any range of days, both ends inclusive. Not widened to whole weeks, unlike
     *   the aggregates the hub reads: an attempt is a session on a date rather than a bucket,
     *   so there is no boundary to round out to — the caller's own days are exactly the
     *   sessions it asked about.
     */
    operator fun invoke(
        exerciseId: Long,
        period: ClosedRange<LocalDate>,
    ): Flow<ExerciseProgression> =
        combine(
            progressionRepository.getExerciseAttempts(exerciseId, period),
            progressionRepository.countExerciseSets(exerciseId),
            personalRecordRepository.getRecordsForExercise(exerciseId),
            // Only one field of the preferences moves this screen, and the object it sits on
            // changes whenever any setting does. Without this, flipping the theme would rebuild
            // every estimate on the chart.
            userPreferencesRepository.observe()
                .map { preferences -> preferences.oneRepMaxFormula }
                .distinctUntilChanged(),
        ) { attempts, totalSets, records, formula ->
            val best = records.bestByType()
            ExerciseProgression(
                attempts = attempts,
                estimates = estimates(attempts, formula),
                records = best,
                newRecords = newRecords(best, attempts.lastOrNull()),
                totalSets = totalSets,
            )
        }

    /**
     * The kinds that were broken in [latest].
     *
     * Both ends of the attempt, because the records are all-time and the attempt is the
     * window's last rather than history's: a record achieved after that session — one logged in
     * a zone whose evening falls on the next UTC day, so the query's window has not reached it
     * — would otherwise be pinned on the session before it, badging a card and starring a row
     * for a lift that was not the one that did it.
     *
     * Inclusive at both ends: the first set of an attempt can itself be the one that set the
     * record, and it is stamped at exactly [ExerciseAttempt.firstSetAt]; the last is stamped at
     * or before the session was closed out, since [PrDetectionUseCase] dates a record by its
     * own set's stamp and runs over the sets of a session already finished.
     */
    private fun newRecords(
        best: Map<PrType, PersonalRecord>,
        latest: ExerciseAttempt?,
    ): Set<PrType> {
        latest ?: return emptySet()
        return best.filterValues { record -> record.achievedAt in latest.firstSetAt..latest.finishedAt }.keys
    }

    /**
     * One projection per attempt whose top set carries both a weight and a rep count.
     *
     * An attempt that carries neither — a run, a timed hold — is **left out** rather than
     * charted as a zero, which would draw a lift collapsing to nothing on the weeks it was
     * done for time. Rounded to the stored milligram exactly as [PrDetectionUseCase] rounds
     * it, so the last point of the line and a record set on the same day agree to the unit.
     */
    private fun estimates(
        attempts: List<ExerciseAttempt>,
        formula: OneRepMaxFormula,
    ): List<ExerciseEstimate> =
        attempts.mapNotNull { attempt ->
            val top = attempt.topSet ?: return@mapNotNull null
            val weight = top.weight?.takeIf { it > 0L } ?: return@mapNotNull null
            val reps = top.reps?.takeIf { it > 0 } ?: return@mapNotNull null
            ExerciseEstimate(
                finishedAt = attempt.finishedAt,
                estimate = OneRepMaxCalculator.calculate(weight, reps, formula).roundToLong(),
            )
        }
}
