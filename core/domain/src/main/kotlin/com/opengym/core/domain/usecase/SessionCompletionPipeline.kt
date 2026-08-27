package com.opengym.core.domain.usecase

import com.opengym.core.domain.gamification.StreakCalculator
import com.opengym.core.domain.model.AchievementProgress
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.SessionProgression
import com.opengym.core.domain.model.SessionProgressionFailure
import com.opengym.core.domain.model.SessionProgressionStep
import com.opengym.core.domain.model.StreakUpdate
import com.opengym.core.domain.model.WorkoutSet
import com.opengym.core.domain.model.XpEvent
import com.opengym.core.domain.model.XpGrant
import com.opengym.core.domain.repository.GamificationRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * Everything that happens *because* a session was finished, in one ordered place.
 *
 * M5 grew five consequences of a finished workout — records detected (#30), XP paid (#33),
 * the streak moved and achievements unlocked (#34), quests advanced (#M5-11) and loot rolled
 * (#M5-13) — each written as a use case that is *given* a finished session and none of them
 * saying who does the giving. This is the giver. It exists so the order is stated once, in
 * code, rather than re-derived by every caller that finishes a workout.
 *
 * ### The order is not free
 *
 * [SessionProgressionStep]'s declaration order is the running order, and three of the four
 * edges are forced:
 *
 * - **PR detection before XP**, because [XpEvent.PrBroken] is emitted once per record
 *   actually beaten, and which records those are is what detection answers.
 * - **PR detection and the streak before achievements**, because
 *   [AchievementProgress] is a snapshot of three lifetime figures at one moment, and two of
 *   them — the records broken and the streak — are figures the two earlier steps have just
 *   moved. `first_record` cannot be evaluated before the record exists.
 * - **Achievements last**, because their own XP is paid inside
 *   [AchievementTriggerUseCase], behind the insert that makes the unlock unrepeatable.
 *
 * Quests and loot plug in after achievements when those issues land; they read the same
 * [AchievementProgress]-shaped view of a session that has already been priced, so nothing
 * above them moves to accommodate them.
 *
 * ### The failure contract: the workout is the user's, the XP is not
 *
 * A step that throws is **caught, recorded in [SessionProgression.failures] and not
 * rethrown**. By the time any of this runs the session row is closed out and its sets are
 * saved; a store that refuses an XP write must not turn a workout that happened into an error
 * the caller reports as "nothing was saved". The opposite choice — rolling a finished session
 * back because a gamification write failed — is the one a transaction-shaped reading reaches
 * for, and it is wrong: the sets are data the user produced and the points are a consequence
 * the app invented. Losing the consequence is a disappointment; losing the workout is a bug
 * the user cannot recover from.
 *
 * Recorded rather than swallowed, though. [SessionProgression.isComplete] is how a caller
 * asks, and the cause is kept so a retry knows which step to run again. Cancellation is not a
 * failure and is rethrown untouched — a caller whose scope went away is not owed a report.
 *
 * A step that failed part way through still reports what it managed to write, where it can:
 * the XP step banks its awards one at a time and each is on file the moment it returns, so
 * [SessionProgression.xp] carries the ones that were paid even when a later one was refused.
 * A result that reported nothing over a total that had moved would be a worse answer than a
 * short one.
 *
 * Steps are independent apart from the ordering above: a failed PR detection leaves the XP
 * step paying for the session and its sets and nothing for records, and the achievements step
 * is skipped outright if the streak could not be read, because a snapshot missing one of its
 * three figures would evaluate the catalogue against a state no moment ever held.
 *
 * ### The re-entry contract: safe to run again, for the same session id
 *
 * A process death between two steps is a state the app can boot into, so running this twice
 * for one session must leave what a single clean run leaves. That is achieved by **each step
 * being individually idempotent**, rather than by a marker that says the cascade as a whole
 * already ran — a marker written at the end would not survive a crash before it, and one
 * written at the start would strand a half-run cascade forever.
 *
 * Three of the four steps are idempotent for free, and were built that way:
 *
 * - detection compares *strictly* better, so a second pass beats the records the first one
 *   wrote and inserts nothing ([PrDetectionUseCase]);
 * - the streak's same-day rule leaves an already-counted session
 *   [com.opengym.core.domain.model.StreakResult.Unchanged] ([StreakCalculator]), given a
 *   store that does not advance `lastCountedAt` for it;
 * - an unlock is insert-if-absent and its XP sits behind the insert's answer
 *   ([AchievementTriggerUseCase]).
 *
 * The fourth is not, and cannot be: [AwardXpUseCase] banks what it is handed, deliberately,
 * so re-emitting a session's events would pay for them twice. That one step is fenced by
 * [GamificationRepository.claimSessionXp] — a per-session claim taken **before** the awards,
 * which chooses under-paying a session torn in half over re-paying every session on every
 * crash-recovery boot. See that method for the whole of the trade.
 *
 * What re-entry therefore produces is an *empty* [SessionProgression], not a repeat of the
 * first one: nothing was broken, nothing was paid, nothing was unlocked, because nothing
 * happened this time.
 *
 * ### What a *torn* run costs, which is not the same question
 *
 * "Run twice" is exact. "Died half way and ran again" is not, and the difference is XP the
 * user never receives, in two places:
 *
 * - a tear **between two awards** leaves the rest of that session's events unpaid, since the
 *   claim was taken before the first of them;
 * - a tear **between detection and the claim** leaves the records' XP unpaid, because the
 *   re-entry's detection beats the rows the first pass wrote and so emits no
 *   [XpEvent.PrBroken] to pay for. The records themselves survive — they were written — and
 *   they still count towards `first_record`, because [AchievementProgress] is built from
 *   what the store holds rather than from what this run happened to do.
 *
 * Both are the same trade taken twice: under-paying a session torn in half, against re-paying
 * one on every crash-recovery boot. Nothing above rounds the loss up, and no step compensates
 * for another's — a cascade that guessed at what a previous pass had already paid is how a
 * user ends up with XP for a workout they did once and the app finished three times.
 *
 * ### Where this is invoked from
 *
 * [FinishSessionUseCase], inside the transition it already holds
 * [SessionLifecycleLock] for. The alternative — a call from the presentation layer after
 * finish returns — was rejected on two counts. It makes every future caller of finish
 * responsible for remembering, and a forgotten call is a workout that silently pays nothing;
 * and it gives up the lock, which is what guarantees no other session transition lands
 * between the row being closed out and the counters this reads being read. Those counters are
 * lifetime totals ([SessionRepository.countFinished],
 * [PersonalRecordRepository.count]), so a session started, finished or discarded in that
 * window would be evaluated into a snapshot that belongs to a different moment than the
 * records and streak beside it.
 *
 * The cost is that the lifecycle lock is held across a dozen small writes rather than four.
 * That is a queue of other *lifecycle transitions*, not of set logging, and the app has one
 * user finishing one workout at a time; a briefly-delayed second start is a better trade than
 * a snapshot assembled out of two different moments. What it does **not** buy is a finish that
 * fails when the cascade does — that is the failure contract above, and it is enforced here
 * rather than by the lock.
 */
class SessionCompletionPipeline(
    private val detectPersonalRecords: PrDetectionUseCase,
    private val awardXp: AwardXpUseCase,
    private val triggerAchievements: AchievementTriggerUseCase,
    private val sessionRepository: SessionRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val gamificationRepository: GamificationRepository,
) {

    /**
     * Runs every step for [sessionId] and returns what each one moved.
     *
     * Never throws for a step that failed — see the failure contract. Safe to call again for a
     * session already processed, in which case the result is empty — see the re-entry
     * contract.
     *
     * @param sessionId the session that has just been closed out. Keyed on the id alone, so a
     *   recovery path that finds an unprocessed session at boot can re-enter with nothing but
     *   the row.
     * @param finishedAt when that session finished — the workout's own moment, read from the
     *   row on a re-entry rather than from a clock, so a record's `achievedAt`, an unlock's
     *   `unlockedAt` and the day the streak counts on all stay the day the training happened.
     */
    suspend operator fun invoke(
        sessionId: Long,
        finishedAt: Instant,
    ): SessionProgression {
        val failures = mutableListOf<SessionProgressionFailure>()

        val records = runStep(SessionProgressionStep.PR_DETECTION, failures) {
            detectPersonalRecords(sessionId)
        }.orEmpty()

        // Collected by the step rather than returned by it: the awards are banked one at a
        // time and each one is on file the moment it returns, so a store that refuses the
        // fifth must not take the four already paid out of the report with it.
        val xp = mutableListOf<XpGrant>()
        runStep(SessionProgressionStep.XP, failures) {
            awardSessionXp(sessionId, records, into = xp)
        }

        val streak = runStep(SessionProgressionStep.STREAK, failures) {
            gamificationRepository.updateStreak(finishedAt) { lastCountedAt, currentStreak ->
                StreakCalculator.onSessionFinished(finishedAt, lastCountedAt, currentStreak)
            }
        }

        val unlocks = runStep(SessionProgressionStep.ACHIEVEMENTS, failures) {
            triggerAchievements(progressAfter(streak), finishedAt)
        }.orEmpty()

        return SessionProgression(
            sessionId = sessionId,
            recordsBroken = records,
            // The unlocks' own awards join the ledger so `xp` is the whole breakdown rather
            // than the part this class emitted; `achievementsUnlocked` keeps them attached to
            // what earned them, which is what a celebration draws.
            xp = xp + unlocks.map { XpGrant(XpEvent.AchievementUnlocked(it.achievement.id), it.xp) },
            streak = streak,
            achievementsUnlocked = unlocks,
            failures = failures,
        )
    }

    /**
     * Banks the session's own awards into [into] — one per set logged, one for the session,
     * one per record — or nothing at all if this session's XP has already been claimed.
     *
     * The claim is taken first and the awards follow it, which is what makes the whole step a
     * no-op on a re-entry; see [GamificationRepository.claimSessionXp].
     *
     * Appended as each one is banked rather than returned as a list at the end, because an
     * award that has returned is already on file. A store that refuses part way through leaves
     * a total that has moved, and a result reporting no XP over it would show "nothing was
     * paid" for a level the user has actually crossed — and the claim is spent, so no later run
     * would correct it. What is lost to the tear is the *rest* of the awards, which is the
     * trade [GamificationRepository.claimSessionXp] names; what was paid is still reported.
     *
     * Sets are counted from the store rather than passed in, so re-entry needs only a session
     * id. Warm-ups count: [XpEvent.SetLogged] prices the work of showing up and does not ask
     * how hard the set was, which is the one place this differs from [PrDetectionUseCase]'s
     * reading of the same rows.
     */
    private suspend fun awardSessionXp(
        sessionId: Long,
        records: List<PersonalRecord>,
        into: MutableList<XpGrant>,
    ) {
        if (!gamificationRepository.claimSessionXp(sessionId)) return

        val setsLogged = sessionRepository.getSetsForSession(sessionId).first().count(WorkoutSet::isCompleted)
        val events = buildList {
            add(XpEvent.SessionFinished)
            repeat(setsLogged) { add(XpEvent.SetLogged) }
            records.forEach { add(XpEvent.PrBroken(it.type)) }
        }
        events.forEach { event -> into += XpGrant(event, awardXp(event)) }
    }

    /**
     * The three lifetime figures the catalogue is evaluated against, as of now.
     *
     * Both counts are **derived** rather than accumulated, which is what makes this step
     * idempotent without a fence of its own: a re-run reads the same numbers, because the
     * steps before it wrote nothing the second time.
     *
     * @throws IllegalStateException if [streak] is null. The dependency is real — a snapshot
     *   with a guessed streak could only ever unlock a milestone late or not at all, but it
     *   would do so against a state that never existed — so a streak step that failed takes
     *   this one with it, recorded as its own failure by the caller rather than silently
     *   evaluating two thirds of the catalogue.
     */
    private suspend fun progressAfter(streak: StreakUpdate?): AchievementProgress {
        val counted = checkNotNull(streak) {
            "achievements cannot be evaluated against a streak that could not be read"
        }
        return AchievementProgress(
            sessionsFinished = sessionRepository.countFinished(),
            currentStreak = counted.days,
            recordsBroken = personalRecordRepository.count(),
        )
    }

    /**
     * Runs one step, returning its value, or **null** having recorded why it could not.
     *
     * `runCatching` rather than a `catch (_: Throwable)` so the cause survives to
     * [failures]; the cancellation check is the part `runCatching` gets wrong on its own,
     * since a coroutine that was cancelled has not failed and must not be reported as a step
     * the store refused.
     */
    private suspend fun <T> runStep(
        step: SessionProgressionStep,
        failures: MutableList<SessionProgressionFailure>,
        block: suspend () -> T,
    ): T? {
        val outcome = runCatching { block() }
        val cause = outcome.exceptionOrNull() ?: return outcome.getOrNull()
        if (cause is CancellationException) throw cause
        failures += SessionProgressionFailure(step, cause)
        return null
    }
}
