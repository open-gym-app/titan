package com.titan.core.domain.usecase

import com.titan.core.domain.model.HatchedPet
import com.titan.core.domain.model.Pet
import com.titan.core.domain.model.PetStats
import com.titan.core.domain.repository.PetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * The pet store, in memory, holding the two rules the real one holds: an egg hatches once, and
 * the first pet is the active one.
 */
class FakePetRepository : PetRepository {

    private val pets = MutableStateFlow<List<Pet>>(emptyList())
    private val eggs = mutableMapOf<Long, Long>()

    /** Every pet held, oldest first. */
    val held: List<Pet> get() = pets.value

    /**
     * Runs just before the next [hatch] writes, once — the window a racing second call lands in.
     */
    var beforeNextHatch: (suspend () -> Unit)? = null

    override fun observeAll(): Flow<List<Pet>> =
        pets.map { all -> all.sortedWith(compareByDescending<Pet> { it.isActive }.thenBy { it.id }) }

    override fun observeActive(): Flow<Pet?> = pets.map { all -> all.firstOrNull { it.isActive } }

    override suspend fun count(): Int = pets.value.size

    override suspend fun setActive(id: Long): Boolean {
        if (pets.value.none { it.id == id }) return false
        pets.value = pets.value.map { it.copy(isActive = it.id == id) }
        return true
    }

    override suspend fun rename(
        id: Long,
        nickname: String?,
    ): Boolean {
        if (pets.value.none { it.id == id }) return false
        pets.value = pets.value.map { if (it.id == id) it.copy(nickname = nickname) else it }
        return true
    }

    override suspend fun findByEgg(collectedItemId: Long): Pet? =
        eggs[collectedItemId]?.let { id -> pets.value.first { it.id == id } }

    override suspend fun hatch(
        collectedItemId: Long,
        speciesId: String,
        hatchedAt: Instant,
    ): HatchedPet {
        beforeNextHatch?.let {
            beforeNextHatch = null
            it()
        }
        findByEgg(collectedItemId)?.let { return HatchedPet(pet = it, isNew = false) }
        val pet = Pet(
            id = pets.value.size + 1L,
            speciesId = speciesId,
            hatchedAt = hatchedAt,
            xp = 0,
            stats = PetStats.ZERO,
            nickname = null,
            isActive = pets.value.isEmpty(),
        )
        eggs[collectedItemId] = pet.id
        pets.value += pet
        return HatchedPet(pet = pet, isNew = true)
    }
}
