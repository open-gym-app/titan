package com.titan.core.data.repository

import androidx.room.withTransaction
import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.db.entity.PetSparEntity
import com.titan.core.domain.model.PowerSnapshot
import com.titan.core.domain.model.SparBout
import com.titan.core.domain.repository.SparRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SparRepositoryImpl @Inject constructor(private val database: TitanDatabase) : SparRepository {

    private val dao get() = database.sparDao()

    override fun observeSnapshots(petId: Long): Flow<List<PowerSnapshot>> =
        dao.observeSnapshots(petId).map { rows -> rows.map { PowerSnapshot(it.weekStart, it.level, it.power) } }

    override fun observeBouts(petId: Long): Flow<List<SparBout>> =
        dao.observeBouts(petId).map { rows -> rows.map { SparBout(it.rival, it.rivalWeek, it.won) } }

    override suspend fun recordBout(
        petId: Long,
        bout: SparBout,
        power: Int,
        rivalPower: Int,
        roundsWon: Int,
        roundsLost: Int,
        foughtAt: Long,
    ) = dao.insertBout(
        PetSparEntity(
            petId = petId,
            rival = bout.kind,
            rivalWeek = bout.rivalWeek,
            power = power,
            rivalPower = rivalPower,
            won = bout.won,
            roundsWon = roundsWon,
            roundsLost = roundsLost,
            foughtAt = foughtAt,
        ),
    )

    override suspend fun sparsWon(): Int = dao.countWins()

    override suspend fun <T> atomically(block: suspend () -> T): T = database.withTransaction { block() }
}
