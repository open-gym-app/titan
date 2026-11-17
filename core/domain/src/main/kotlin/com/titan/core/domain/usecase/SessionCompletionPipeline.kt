package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.SessionXp
import com.titan.core.domain.gamification.StreakCalculator
import com.titan.core.domain.model.AchievementProgress
import com.titan.core.domain.model.AchievementUnlock
import com.titan.core.domain.model.PersonalRecord
import com.titan.core.domain.model.PetGrowth
import com.titan.core.domain.model.QuestCompletion
import com.titan.core.domain.model.SessionProgression
import com.titan.core.domain.model.SessionProgressionFailure
import com.titan.core.domain.model.SessionProgressionStep
import com.titan.core.domain.model.StreakUpdate
import com.titan.core.domain.model.XpEvent
import com.titan.core.domain.model.XpGrant
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.PersonalRecordRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * Everything that happens *because* a session was finished, in one ordered place.
 *
 * M5 grew five consequences of a finished workout — records detected (#30), XP paid (#33),
 * the streak moved and achievements unlocked (#34), quests advanced (#105) and loot rolled
 * (#107) — each written as a use case that is *given* a finished session and none of them
 * saying who does the giving. This is the giver. It exists so the order is stated once, in
 * code, rather than re-derived by every caller that finishes a workout.
 *
 * ### The order is not free
 *
 * [SessionProgressionStep]'s declaration order is the running order, and all but two of
 * its edges are forced:
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
 * - **Quests after PR detection**, for the same reason achievements are: a quest that
 *   completes on a record cannot see one that has not been written yet.
 *
 * Quests are ordered *after* achievements rather than beside them only because one order has
 * to be written down; they do not read [AchievementProgress] and are not fenced by the streak.
 *
 * **Loot is the other free position, and the freer of the two.** A roll reads none of the
 * figures the steps above it write, and none of them reads a drop; it is last because it
 * arrived last (#171) and nothing above it had to move to let it in. What it needs instead of
 * an ordering is a fence of its own — see the re-entry contract below.
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
 * A step that failed part way through still reports what it managed to write. Four of the
 * five writing steps work one row at a time — a record per candidate, an award per event, an
 * unlock per milestone, a completion per quest — and each is committed the moment its call
 * returns, so each fills a list this class owns instead of returning one at the end.
 * [SessionProgression] therefore carries the records that were written, the XP that was paid,
 * the achievements unlocked and the quests completed before the refusal, alongside the
 * [SessionProgression.failures] entry saying the step did not finish. A result that reported
 * nothing over a store that had moved would be a worse answer than a short one — and in every
 * one of those cases it would be unrecoverable, because a re-entry beats no record it already
 * wrote, unlocks no achievement it already holds, completes no quest already banked for the
 * period, and finds the session's XP and loot claims already spent.
 *
 * The fifth, loot, needs no such list: it writes at most one row, so a step that threw wrote
 * nothing at all and a plain return value loses nothing to the throw.
 *
 * Steps are independent apart from the ordering above: a PR detection that fails part way
 * leaves the XP step paying for the session, its sets and the records that did land, and the
 * achievements step is skipped outright if the streak could not be read, because a snapshot
 * missing one of its three figures would evaluate the catalogue against a state no moment
 * ever held.
 *
 * ### The re-entry contract: safe to run again, for the same session id
 *
 * A process death between two steps is a state the app can boot into, so running this twice
 * for one session must leave what a single clean run leaves. That is achieved by **each step
 * being individually idempotent**, rather than by a marker that says the cascade as a whole
 * already ran — a marker written at the end would not survive a crash before it, and one
 * written at the start would strand a half-run cascade forever.
 *
 * Four of the seven steps are idempotent for free, and were built that way:
 *
 * - detection compares *strictly* better, so a second pass beats the records the first one
 *   wrote and inserts nothing ([PrDetectionUseCase]);
 * - the streak counts a session only if it finished after the last one counted, which leaves
 *   an already-counted session [com.titan.core.domain.model.StreakResult.Unchanged]
 *   ([StreakCalculator]) and is also what stops a re-entry spending a second streak
 *   protection on the same short week — see [StreakCalculator.advance];
 * - an unlock is insert-if-absent and its XP sits behind the insert's answer
 *   ([AchievementTriggerUseCase]);
 * - a quest completion is the same insert-if-absent, narrowed to a period, with its XP behind
 *   the same answer ([QuestCompletionUseCase]).
 *
 * The remaining three are not, and cannot be. [AwardXpUseCase] banks what it is handed,
 * deliberately, so re-emitting a session's events would pay for them twice; and a roll is a
 * decision taken from a random source, so re-entering would roll again and hand out a second
 * collectible — and unlike an unlock there is nothing downstream to catch it, since a second
 * legitimate drop of the same collectible is a normal thing to hold. Each is fenced by a claim
 * of its own, taken **before** the step it protects:
 * [GamificationRepository.claimSessionXp] and [GamificationRepository.claimSessionLoot]. The
 * third is the pet: growth is an increment to the active pet's XP and stats, so a re-entry
 * would add it again, and [GamificationRepository.claimSessionPet] fences it on the same terms
 * — see [GrowPetUseCase], which also says why the claim is spent when there is no pet to pay.
 * All three choose under-paying a session torn in half over re-paying every session on every
 * crash-recovery boot. See [GamificationRepository.claimSessionXp] for the whole of the trade,
 * and [LootRollUseCase] for why the roll takes the same one rather than a cleverer answer.
 *
 * Two claims rather than one shared marker, because they fence two independent steps: a run
 * that died after the awards and before the roll must still be able to roll on re-entry, which
 * a single "the cascade ran" row could not express.
 *
 * What re-entry therefore produces is an *empty* [SessionProgression], not a repeat of the
 * first one: nothing was broken, nothing was paid, nothing was unlocked and nothing dropped,
 * because nothing happened this time.
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
 * A third, in a currency of its own: a tear **between the loot claim and the drop's row** costs
 * the session its collectible outright, since the claim is spent and no later run rolls again.
 * It is the rarer loss of the three — four sessions in five roll nothing to lose — and the
 * alternative is a device that drops a collectible on every crash-recovery boot.
 *
 * All of them are the same trade taken again: under-paying a session torn in half, against
 * re-paying one on every crash-recovery boot. Nothing above rounds the loss up, and no step
 * compensates for another's — a cascade that guessed at what a previous pass had already paid
 * is how a user ends up with XP for a workout they did once and the app finished three times.
 *
 * ### The pet step is last, and reads nothing the others write
 *
 * [SessionProgressionStep.PET] runs after loot, on loot's terms: nothing above it moves. It is
 * paid from the session's own sets rather than from the XP this run awarded, so — like quests
 * with the streak — a failed XP or detection step does not take it down, and a run that died
 * after XP and is re-entered still pays the pet in full.
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
    private val completeQuests: QuestCompletionUseCase,
    private val rollLoot: LootRollUseCase,
    private val growPet: GrowPetUseCase,
    private val evaluateUnlocks: EvaluateUnlocksUseCase,
    private val sessionRepository: SessionRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val gamificationRepository: GamificationRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
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
     *   `unlockedAt` and the week the streak counts it in all stay the ones the training
     *   happened in.
     */
    suspend operator fun invoke(
        sessionId: Long,
        finishedAt: Instant,
    ): SessionProgression {
        val failures = mutableListOf<SessionProgressionFailure>()

        // Each of the four list-filling steps fills a list this method owns rather than
        // returning one, because `runStep` drops a step's return value when it throws and every
        // write these make is committed the moment it returns. A store that refuses the fifth of
        // nine must not take the four already on file out of the report with it. Loot is the
        // writer that needs none — see its own comment below.
        val records = mutableListOf<PersonalRecord>()
        val beaten = mutableListOf<PersonalRecord>()
        runStep(SessionProgressionStep.PR_DETECTION, failures) {
            detectPersonalRecords(sessionId, into = records, beaten = beaten)
        }

        val xp = mutableListOf<XpGrant>()
        runStep(SessionProgressionStep.XP, failures) {
            awardSessionXp(sessionId, beaten, into = xp)
        }

        val streak = runStep(SessionProgressionStep.STREAK, failures) {
            // Read before the transaction rather than inside it: the transform must be pure, and
            // the goal in force is the one the user has set at the moment the session is applied.
            val weeklyGoal = userPreferencesRepository.observe().first().weeklyWorkoutGoal
            gamificationRepository.updateStreak { state -> StreakCalculator.advance(state, finishedAt, weeklyGoal) }
        }

        val unlocks = mutableListOf<AchievementUnlock>()
        runStep(SessionProgressionStep.ACHIEVEMENTS, failures) {
            triggerAchievements(progressAfter(streak), finishedAt, into = unlocks)
        }

        // Not fenced by the streak the way achievements are: no quest counts streak weeks, so a
        // streak step that failed leaves this one evaluating a board that is entirely intact.
        val quests = mutableListOf<QuestCompletion>()
        runStep(SessionProgressionStep.QUESTS, failures) {
            completeQuests(finishedAt, into = quests)
        }

        // The one step whose result is a plain return value rather than a list this method
        // owns: at most one row is written, so there is no partial write for a throw to lose.
        // Null therefore covers three answers — the table paid nothing, the session had already
        // been rolled for, or the step failed — and `failures` is where the third is stated.
        val loot: LootRoll? = runStep(SessionProgressionStep.LOOT, failures) {
            rollLoot.roll(sessionId, finishedAt)
        }

        val petGrowth: PetGrowth? = runStep(SessionProgressionStep.PET, failures) {
            growPet(sessionId)
        }

        val unlocked = runStep(SessionProgressionStep.UNLOCKS, failures) {
            evaluateUnlocks(finishedAt.toEpochMilli())
        }.orEmpty()

        return SessionProgression(
            sessionId = sessionId,
            recordsBroken = records,
            // The unlocks' own awards join the ledger so `xp` is the whole breakdown rather
            // than the part this class emitted; `achievementsUnlocked` keeps them attached to
            // what earned them, which is what a celebration draws.
            xp = xp +
                unlocks.map { XpGrant(XpEvent.AchievementUnlocked(it.achievement.id), it.xp) } +
                quests.map { XpGrant(XpEvent.QuestCompleted(it.quest.id, it.quest.xpReward), it.xp) },
            streak = streak,
            achievementsUnlocked = unlocks,
            questsCompleted = quests,
            lootDropped = loot?.drop,
            foodDropped = loot?.food.orEmpty(),
            pet = petGrowth,
            unlocked = unlocked,
            failures = failures,
        )
    }

    /**
     * Banks the session's own awards into [into] — the session, its working sets and the
     * records it beat, as [SessionXp] decides — or nothing at all if this session's XP has
     * already been claimed.
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
     * Sets are read from the store rather than passed in, so re-entry needs only a session id.
     * Warm-ups and sets with nothing logged on them do not pay, and neither does a record that
     * was the first figure ever written for its exercise: the first time an exercise is done,
     * every record kind it is eligible for is written because there is nothing to compare with,
     * and those are baselines, not feats. [beatenRecords] is therefore detection's own account
     * of which records found one on file to beat — see [PrDetectionUseCase.invoke] — rather
     * than every record the session wrote.
     *
     * The events are worked out before the claim is taken, because working them out is only
     * a read: a read that fails then leaves the claim unspent for a re-entry to pay, where after
     * the claim it would cost the session every award it had earned.
     */
    private suspend fun awardSessionXp(
        sessionId: Long,
        beatenRecords: List<PersonalRecord>,
        into: MutableList<XpGrant>,
    ) {
        val sets = sessionRepository.getSetsForSession(sessionId).first()
        val events = SessionXp.eventsFor(sets, beatenRecords)

        if (!gamificationRepository.claimSessionXp(sessionId)) return

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
            currentStreak = counted.weeks,
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
