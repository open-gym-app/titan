package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.Unlocks
import com.titan.core.domain.gamification.XpCalculator
import com.titan.core.domain.model.UnlockProgress
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.SessionRepository
import com.titan.core.domain.repository.SparTally
import com.titan.core.domain.repository.UnlockRepository
import kotlinx.coroutines.flow.first

/**
 * Stores whatever the user's training has newly earned.
 *
 * Runs after a finished session (the cascade's last step) and after a spar. Idempotent by the
 * store's key: a re-run writes nothing new, and an unlock is never taken back when its condition
 * lapses.
 *
 * It reads the user's training — the streak, the distance, the level — and nothing it writes is
 * ever read back by XP, records, the streak, quests or drops.
 */
class EvaluateUnlocksUseCase(
    private val gamificationRepository: GamificationRepository,
    private val sessionRepository: SessionRepository,
    private val sparTally: SparTally,
    private val unlockRepository: UnlockRepository,
) {

    /** @return the keys this call unlocked, in no particular order. */
    suspend operator fun invoke(at: Long): Set<String> {
        val progress = gamificationRepository.observeProgress().first()
        val distance = sessionRepository.getTotals().first().values
            .fold(0L) { total, session -> saturatingAdd(total, session.totalDistance) }

        val earned = Unlocks.earned(
            UnlockProgress(
                bestStreakWeeks = maxOf(progress?.currentStreak ?: 0, progress?.longestStreak ?: 0),
                distanceCentimetres = distance,
                level = progress?.level ?: XpCalculator.FIRST_LEVEL,
                sparsWon = sparTally.sparsWon(),
            ),
        )
        return if (earned.isEmpty()) emptySet() else unlockRepository.unlock(earned, at)
    }

    private fun saturatingAdd(
        a: Long,
        b: Long,
    ): Long = if (b > 0 && a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b.coerceAtLeast(0)
}
