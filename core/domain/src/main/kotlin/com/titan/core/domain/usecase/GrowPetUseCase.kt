package com.titan.core.domain.usecase

import com.titan.core.domain.gamification.PetGrowthRates
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PetGain
import com.titan.core.domain.model.PetGrowth
import com.titan.core.domain.repository.ExerciseRepository
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.PetRepository
import com.titan.core.domain.repository.SessionRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Grows the active pet from one finished session, once.
 *
 * ### The fence
 *
 * Growth is an increment, so a re-entered cascade would repeat it. It takes the answer the XP
 * and loot steps already take: a per-session claim, taken **before** the write, in one
 * uncancellable unit with it.
 *
 * **With no active pet the claim is still spent.** Otherwise hatching a first pet would let
 * every past session be re-entered and bill it retroactively — `session_loot_claim`'s rule that a
 * claim is spent whether or not the roll paid.
 *
 * The pet that grows is the one active when this runs.
 *
 * ### Null has four answers
 *
 * The session was already claimed, there is no active pet, the session holds no paid set, or —
 * as the pipeline reports it — the step failed. None of them is something to draw.
 */
class GrowPetUseCase(
    private val sessionRepository: SessionRepository,
    private val exerciseRepository: ExerciseRepository,
    private val petRepository: PetRepository,
    private val gamificationRepository: GamificationRepository,
) {

    suspend operator fun invoke(
        sessionId: Long,
        finishedAt: Instant,
    ): PetGrowth? {
        // Read before the claim, as the XP step does: a read that throws leaves the claim
        // untaken, and the re-entered run pays in full.
        val gain = gainFor(sessionId)

        return withContext(NonCancellable) {
            if (!gamificationRepository.claimSessionPet(sessionId)) return@withContext null
            if (gain.isNothing) return@withContext null
            petRepository.grow(gain, at = finishedAt.toEpochMilli())
        }
    }

    private suspend fun gainFor(sessionId: Long): PetGain {
        val exerciseIdBySlot = sessionRepository.getExercises(sessionId).first().associate { it.id to it.exerciseId }
        val typeByExercise = exerciseRepository.getByIds(exerciseIdBySlot.values.toSet()).associate { it.id to it.type }

        // An exercise the catalogue does not hold is a lift, the call `PrDetectionUseCase` makes.
        // The schema's RESTRICT means production never meets one.
        val sets = sessionRepository.getSetsForSession(sessionId).first().map { set ->
            set to (typeByExercise[exerciseIdBySlot[set.sessionExerciseId]] ?: ExerciseType.STRENGTH)
        }
        return PetGrowthRates.gainFor(sets)
    }
}
