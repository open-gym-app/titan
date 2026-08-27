package com.opengym.core.domain.usecase

import com.opengym.core.domain.gamification.Achievements
import com.opengym.core.domain.model.Achievement
import com.opengym.core.domain.model.AchievementProgress
import com.opengym.core.domain.model.AchievementUnlock
import com.opengym.core.domain.model.XpEvent
import com.opengym.core.domain.repository.GamificationRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Unlocks every achievement a finished session newly qualifies the user for, and pays each
 * one's XP — never twice.
 *
 * The catalogue is data ([Achievements]), so this has no per-achievement branch and gains
 * none when the set grows: it evaluates [Achievement.isUnlockedBy] over [Achievements.ALL]
 * and writes what it finds. What it does own is the *once*, which is two separate problems.
 *
 * ### Not unlocking twice
 *
 * [Achievement.isUnlockedBy] stays true forever once a threshold is cleared — a user with 40
 * sessions qualifies for `ten_sessions` on every session they ever finish again. So the
 * catalogue is filtered against [GamificationRepository.unlockedAchievementIds] before
 * anything is written, and the write itself is `insertIfAbsent`. The read is the cheap filter
 * and the insert is the guarantee: only the call that actually wrote the row is told it
 * unlocked anything, so a second cascade for the same session — a retry after a crash, the
 * re-entry #145 has to survive — finds nothing new to report.
 *
 * ### Not paying twice
 *
 * #33 deliberately does not deduplicate: [AwardXpUseCase] banks what it is handed, so an
 * [XpEvent.AchievementUnlocked] emitted twice is paid twice, and not emitting it twice is
 * this class's job. That is why the XP call sits *after* the insert and behind its answer,
 * rather than beside it — the atomic write is the only thing that can tell a first unlock
 * from a repeat, and awarding before it would pay for both.
 *
 * What that ordering buys is *at most* once, not exactly once, and the gap is worth naming.
 * The insert commits first and is what makes the unlock unrepeatable, so anything that stops
 * the award afterwards leaves the achievement held and its XP owed forever: every later run
 * gets `false` back from `insertIfAbsent` and never reaches the award again. Cancellation is
 * closed off here — [unlock] runs the pair uncancellably — but a store that *fails* the award
 * is not, and cannot be from this layer. Under-paying one achievement is the better half of
 * the trade against re-paying every re-run, and closing it properly means writing the row and
 * the XP in one transaction, which is #35's to give.
 *
 * ### Where the figures come from
 *
 * [AchievementProgress] is passed in, not read here: the cascade (#145) has just detected the
 * session's records and recomputed its streak, and it is the only place all three counters
 * are known at one consistent moment. **This must therefore run after PR detection and after
 * the streak update** — an achievement that unlocks on a first record cannot be evaluated
 * before the record exists — which is an ordering #145 owns and this class only depends on.
 */
class AchievementTriggerUseCase(
    private val gamificationRepository: GamificationRepository,
    private val awardXp: AwardXpUseCase,
    private val catalogue: List<Achievement> = Achievements.ALL,
) {

    /**
     * Unlocks whatever [progress] newly qualifies for, in [catalogue] order, and returns the
     * unlocks that happened here — empty when nothing new was earned, which is the ordinary
     * case for most sessions.
     *
     * @param unlockedAt when the qualifying session finished, stamped on the unlock and
     *   nowhere derived from a clock: a cascade re-run an hour later must date the unlock to
     *   the workout that earned it, the same rule [PrDetectionUseCase] follows for a record's
     *   `achievedAt`.
     */
    suspend operator fun invoke(
        progress: AchievementProgress,
        unlockedAt: Instant,
    ): List<AchievementUnlock> {
        val alreadyUnlocked = gamificationRepository.unlockedAchievementIds()
        return catalogue
            .filter { it.id !in alreadyUnlocked && it.isUnlockedBy(progress) }
            .mapNotNull { achievement -> unlock(achievement, unlockedAt) }
    }

    /** The unlock, or null when the row was already there — see the class doc. */
    private suspend fun unlock(
        achievement: Achievement,
        unlockedAt: Instant,
    ): AchievementUnlock? =
        // Uncancellable as one piece: the insert is what makes the unlock unrepeatable, so a
        // cancellation landing between it and the award — a screen's scope going away while
        // the cascade runs — would owe that XP for the life of the install, since no later
        // run gets past the insert to pay it. Two small writes, with nothing worth abandoning
        // half way between them, which is the same trade [DiscardSessionUseCase] makes.
        withContext(NonCancellable) {
            if (!gamificationRepository.unlockAchievement(achievement.id, unlockedAt)) {
                return@withContext null
            }
            AchievementUnlock(
                achievement = achievement,
                unlockedAt = unlockedAt,
                xp = awardXp(XpEvent.AchievementUnlocked(achievement.id)),
            )
        }
}
