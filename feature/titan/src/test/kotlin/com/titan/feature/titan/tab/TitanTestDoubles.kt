package com.titan.feature.titan.tab

import com.titan.core.domain.gamification.Collectibles
import com.titan.core.domain.gamification.TitanRules
import com.titan.core.domain.model.CollectedItem
import com.titan.core.domain.model.GearSlot
import com.titan.core.domain.model.HatchedPet
import com.titan.core.domain.model.PantryEntry
import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.PetGain
import com.titan.core.domain.model.PetGrowth
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.model.TitanChange
import com.titan.core.domain.model.TitanState
import com.titan.core.domain.model.UnlockedAchievement
import com.titan.core.domain.repository.CollectedItemRepository
import com.titan.core.domain.repository.GamificationRepository
import com.titan.core.domain.repository.LoadoutRepository
import com.titan.core.domain.repository.PantryRepository
import com.titan.core.domain.repository.PetRepository
import com.titan.core.domain.repository.TitanStateRepository
import com.titan.core.domain.repository.UnlockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.lang.reflect.Proxy
import java.time.Instant

/*
 * In-memory stores for the Titan tab's ViewModel: just enough of each contract for the tab's
 * reads and the use cases it calls.
 */

class FakePets : PetRepository {

    val pets = MutableStateFlow<List<Pet>>(emptyList())

    override fun observeAll(): Flow<List<Pet>> = pets

    override fun observeActive(): Flow<Pet?> = pets.map { all -> all.firstOrNull { it.isActive } }

    override suspend fun count(): Int = pets.value.size

    override suspend fun setActive(id: Long): Boolean = true

    override suspend fun rename(
        id: Long,
        nickname: String?,
    ): Boolean {
        pets.value = pets.value.map { if (it.id == id) it.copy(nickname = nickname) else it }
        return true
    }

    override suspend fun findByEgg(collectedItemId: Long): Pet? = pets.value.firstOrNull { it.id == collectedItemId }

    override suspend fun hatch(
        collectedItemId: Long,
        speciesId: String,
        hatchedAt: Instant,
    ): HatchedPet {
        val pet = Pet(collectedItemId, speciesId, hatchedAt, 0, PetStats.ZERO, null, isActive = pets.value.isEmpty())
        pets.value += pet
        return HatchedPet(pet, isNew = true)
    }

    override suspend fun grow(gain: PetGain): PetGrowth? = null
}

class FakeTitans(private val now: Long) : TitanStateRepository {

    val states = MutableStateFlow<Map<Long, TitanState>>(emptyMap())

    override fun observe(petId: Long): Flow<TitanState?> = states.map { it[petId] ?: TitanRules.starting(now) }

    override suspend fun <R> modify(
        petId: Long,
        change: (state: TitanState, now: Long) -> TitanChange<R>,
    ): R? {
        val decided = change(states.value[petId] ?: TitanRules.starting(now), now)
        decided.state?.let { states.value += petId to it }
        return decided.result
    }
}

class FakePantry : PantryRepository {

    val stock = MutableStateFlow(mapOf("steak" to 1))
    val eaten = mutableListOf<String>()

    override fun observePantry(): Flow<List<PantryEntry>> =
        stock.map { counts ->
            Collectibles.FOOD.map {
                PantryEntry(
                    it,
                    counts[it.id] ?: 0,
                    isNew = false,
                    newestFrom = null,
                )
            }
        }

    override suspend fun oldestInStock(foodId: String): Long? = if ((stock.value[foodId] ?: 0) > 0) 1L else null

    override suspend fun consume(
        collectedItemId: Long,
        petId: Long,
        fedAt: Long,
    ) {
        val id = stock.value.entries.first { it.value > 0 }.key
        eaten += id
        stock.value += id to (stock.value.getValue(id) - 1)
    }

    override suspend fun markSeen(foodId: String) = Unit

    override suspend fun <T> atomically(block: suspend () -> T): T = block()
}

class FakeUnlocks : UnlockRepository {

    val stored = MutableStateFlow<Set<String>>(emptySet())

    override fun observeUnlocked(): Flow<Set<String>> = stored

    override suspend fun unlock(
        keys: Set<String>,
        at: Long,
    ): Set<String> {
        val new = keys - stored.value
        stored.value += new
        return new
    }
}

class FakeLoadout : LoadoutRepository {

    val rows = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun observeLoadout(petId: Long): Flow<Map<String, String>> = rows

    override suspend fun wear(
        petId: Long,
        slot: GearSlot,
        gearKey: String,
    ) {
        rows.value += slot.key to gearKey
    }

    override suspend fun takeOff(
        petId: Long,
        slotKey: String,
    ) {
        rows.value -= slotKey
    }
}

class FakeEggs : CollectedItemRepository {

    val eggs = MutableStateFlow<List<CollectedItem>>(emptyList())

    override fun observeRecent(limit: Int): Flow<List<CollectedItem>> = eggs

    override suspend fun recordDrop(
        collectibleId: String,
        source: String,
        acquiredAt: Instant,
        sessionId: Long?,
    ) = Unit

    override suspend fun find(id: Long): CollectedItem? = eggs.value.firstOrNull { it.id == id }

    override fun observeUnhatchedEggs(): Flow<List<CollectedItem>> = eggs
}

/**
 * The achievements held, and nothing else of [GamificationRepository]: the tab reads only these,
 * so anything else it calls fails loudly rather than answering with a made-up value.
 */
class FakeAchievements(held: Set<String> = emptySet()) : GamificationRepository by unsupported() {
    val held = MutableStateFlow(held)

    override fun observeUnlockedAchievements(): Flow<List<UnlockedAchievement>> =
        held.map { ids -> ids.map { UnlockedAchievement(it, Instant.EPOCH) } }

    override suspend fun unlockedAchievementIds(): Set<String> = held.value
}

private fun unsupported(): GamificationRepository =
    Proxy.newProxyInstance(
        GamificationRepository::class.java.classLoader,
        arrayOf(GamificationRepository::class.java),
    ) { _, method, _ ->
        error("${method.name} is not faked")
    } as GamificationRepository
