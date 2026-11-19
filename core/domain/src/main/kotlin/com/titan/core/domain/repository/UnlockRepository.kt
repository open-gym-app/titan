package com.titan.core.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * What the user has unlocked for the titan — places now, gear later — kept once earned.
 *
 * A key this build does not know (a newer build's unlock, restored) is kept and returned like
 * any other; the readers that look keys up ignore what they cannot name.
 */
interface UnlockRepository {

    /** Every unlocked key. */
    fun observeUnlocked(): Flow<Set<String>>

    /**
     * Stores [keys] as unlocked at [at], keeping the moment of any already held.
     *
     * @return the keys that were not held before: what this call unlocked.
     */
    suspend fun unlock(
        keys: Set<String>,
        at: Long,
    ): Set<String>
}
