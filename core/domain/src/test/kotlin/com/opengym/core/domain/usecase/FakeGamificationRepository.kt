package com.opengym.core.domain.usecase

import com.opengym.core.domain.gamification.XpCalculator
import com.opengym.core.domain.model.StreakProtection
import com.opengym.core.domain.model.StreakState
import com.opengym.core.domain.model.StreakUpdate
import com.opengym.core.domain.model.UnlockedAchievement
import com.opengym.core.domain.model.UserProgress
import com.opengym.core.domain.model.XpAward
import com.opengym.core.domain.repository.GamificationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * The progress row, in memory.
 *
 * Both levels are derived by calling [GamificationRepository.addXp]'s `levelForTotalXp` on the
 * totals either side of the delta, exactly as the contract requires of a real implementation —
 * a fake that stored a level of its own choosing, or that reused a level column for the
 * previous one, could let a test pass on a total and level the database could never hold
 * together.
 *
 * [unlockAchievement] is likewise insert-if-absent and reports whether it wrote, since that
 * answer is what the trigger use case pays XP on; a fake that always said `true` would let an
 * idempotency test pass against a store that re-awards every session. [claimSessionXp] is the
 * same shape for the same reason: the cascade's re-entry contract rests entirely on the second
 * claim answering `false`. [claimSessionLoot] is a second, independent set of markers, because
 * the two fence separate steps and a fake that shared one would let a cascade torn between them
 * pass a test it should fail.
 *
 * [updateStreak] persists whatever the transition hands it and nothing else, which is exactly
 * what the contract asks of a real store: the rules about what to advance and what to spend
 * live in `StreakCalculator`, and a fake that applied a rule of its own would be testing a
 * second implementation of them.
 *
 * Single-threaded, like the tests that use it, so it does not reproduce the real store's
 * transactional serialisation; the awards below are never actually interleaved.
 */
class FakeGamificationRepository(initialXp: Long = 0L) : GamificationRepository {

    var totalXp: Long = initialXp
        private set

    /** The streak row, such as it is — the state the store persists between sessions. */
    private val streak = MutableStateFlow(StreakState())

    val streakDays: Int get() = streak.value.currentStreak

    val lastCountedAt: Instant? get() = streak.value.lastCountedAt

    /** What is left of the protection allowance, as stored. */
    val protection: StreakProtection get() = streak.value.protection

    /**
     * The level last written beside [totalXp] — the column, not a derivation.
     *
     * Written from [GamificationRepository.addXp]'s own `levelForTotalXp` on the new total,
     * which is what the contract requires of a real store: a fake that derived it on read
     * could not reproduce a row written under an older curve.
     */
    var currentLevel: Int = XpCalculator.FIRST_LEVEL
        private set

    /** Whether anything has ever been written — the difference between no row and a zeroed one. */
    private var written = false

    /** The best [streakDays] ever reached, as the real row keeps it. */
    val longestStreak: Int get() = streak.value.longestStreak

    /**
     * Bumped by every write, so [observeUnlockedAchievements] has something to re-emit on.
     *
     * The rest of this fake's state is plain mutable fields, which a `Flow` cannot follow. One
     * counter over all of them rather than a flow per field: what a consumer needs is to be
     * woken when *something* changed and to re-read, which is what a real store's table
     * observer does anyway.
     */
    private val revision = MutableStateFlow(0)

    /** Sessions whose XP has been claimed — the marker row, such as it is. */
    private val xpClaims = mutableSetOf<Long>()

    /** The same, for the roll: a separate set, because the two claims fence separate steps. */
    private val lootClaims = mutableSetOf<Long>()

    /**
     * Thrown instead of banking, writing or reading, when set. The only way to reach the
     * cascade's failure contract: a store that refuses a write is not a state any argument a
     * caller can pass produces.
     */
    var addXpFailure: Throwable? = null

    var streakFailure: Throwable? = null

    var achievementFailure: Throwable? = null

    /** Every award granted, oldest first. */
    val awards = mutableListOf<XpAward>()

    private val unlocked = mutableMapOf<String, Instant>()

    /**
     * What has been unlocked, and when — the `achievement` table, such as it is.
     *
     * A copy, so a test can hold one of these and compare a later state against it rather
     * than against a view that moved with the store.
     */
    val unlockedAchievements: Map<String, Instant> get() = unlocked.toMap()

    /**
     * The row as a screen reads it, or null while nothing has been written.
     *
     * Read when it is collected rather than pushed on every write: nothing in the domain's own
     * tests watches this while it changes, and a fake that maintained a hot flow would be
     * carrying machinery no test exercises.
     */
    override fun observeProgress(): Flow<UserProgress?> = flow { emit(stored()) }

    /** The row as it stands, or null while nothing has ever been written to it. */
    private fun stored(): UserProgress? =
        if (!written) {
            null
        } else {
            UserProgress(
                totalXp = totalXp,
                level = currentLevel,
                currentStreak = streakDays,
                longestStreak = longestStreak,
                lastCountedAt = lastCountedAt,
            )
        }

    override suspend fun addXp(
        delta: Int,
        levelForTotalXp: (totalXp: Long) -> Int,
    ): XpAward {
        addXpFailure?.let { throw it }
        val previousLevel = levelForTotalXp(totalXp)
        totalXp += delta
        currentLevel = levelForTotalXp(totalXp)
        written = true
        revision.value++
        return XpAward(
            awarded = delta,
            totalXp = totalXp,
            previousLevel = previousLevel,
            level = levelForTotalXp(totalXp),
        ).also(awards::add)
    }

    override suspend fun unlockedAchievementIds(): Set<String> {
        achievementFailure?.let { throw it }
        return unlocked.keys.toSet()
    }

    override suspend fun unlockAchievement(
        achievementId: String,
        unlockedAt: Instant,
    ): Boolean = (unlocked.putIfAbsent(achievementId, unlockedAt) == null)
        .also { if (it) revision.value++ }

    override suspend fun claimSessionXp(sessionId: Long): Boolean = xpClaims.add(sessionId)

    override suspend fun claimSessionLoot(sessionId: Long): Boolean = lootClaims.add(sessionId)

    override suspend fun updateStreak(advance: (state: StreakState) -> StreakUpdate): StreakUpdate {
        streakFailure?.let { throw it }
        val update = advance(streak.value)
        streak.value = update.state
        written = true
        revision.value++
        return update
    }

    override fun observeStreak(): Flow<StreakState> = streak.asStateFlow()

    /** Seeds a streak already on file, as a user with history would have. */
    fun seedStreak(
        days: Int,
        lastCountedAt: Instant,
        protection: StreakProtection = StreakProtection(),
    ) {
        streak.value = StreakState(
            currentStreak = days,
            longestStreak = days,
            lastCountedAt = lastCountedAt,
            protection = protection,
        )
        written = true
        revision.value++
    }

    /*
     * The unlock list (#104), driven off [revision]: it re-reads the map above whenever a write
     * bumps it, which is as reactive as a store whose state is plain mutable fields can honestly
     * be. [observeProgress] above is not driven off it — nothing in the domain's own tests
     * watches the row while it changes, and the two reads are free to differ on that.
     */

    override fun observeUnlockedAchievements(): Flow<List<UnlockedAchievement>> =
        revision.map {
            unlocked
                .map { (id, at) -> UnlockedAchievement(achievementId = id, unlockedAt = at) }
                .sortedWith(compareBy({ it.unlockedAt }, { it.achievementId }))
        }
}
