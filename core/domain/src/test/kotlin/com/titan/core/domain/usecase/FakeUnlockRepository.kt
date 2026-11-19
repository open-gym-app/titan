package com.titan.core.domain.usecase

import com.titan.core.domain.repository.UnlockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The unlock store, in memory: a key is stored once, with the moment it was first earned. */
class FakeUnlockRepository : UnlockRepository {

    private val held = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Every key held, with when it was earned. */
    val unlocked: Map<String, Long> get() = held.value

    override fun observeUnlocked(): Flow<Set<String>> = held.map { it.keys }

    override suspend fun unlock(
        keys: Set<String>,
        at: Long,
    ): Set<String> {
        val new = keys - held.value.keys
        held.value += new.associateWith { at }
        return new
    }
}
