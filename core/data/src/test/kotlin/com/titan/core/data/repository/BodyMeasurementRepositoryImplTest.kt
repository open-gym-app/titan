package com.titan.core.data.repository

import com.titan.core.data.db.RoomTestBase
import com.titan.core.data.mapper.BodyMeasurementMapper
import com.titan.core.domain.model.BodyMeasurement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body measurement history through the repository, over a real SQLite.
 *
 * What the tests are about is the round trip: every field the body screen offers has to come
 * back as it went in, and a field the user left blank has to come back blank rather than as a
 * zero somebody could mistake for a measurement of nothing.
 */
class BodyMeasurementRepositoryImplTest : RoomTestBase() {

    private companion object {
        /** A full entry, so a dropped field shows up as an inequality rather than a null. */
        val FULL = BodyMeasurement(
            weight = 79_800_000,
            chest = 1_040,
            waist = 820,
            arm = 385,
            thigh = 610,
            bodyFat = 155,
            notes = "morning, fasted",
            createdAt = 1_700_000_000_000,
            updatedAt = 1_700_000_000_000,
        )
    }

    private val repository by lazy {
        BodyMeasurementRepositoryImpl(
            bodyMeasurementDao = db.bodyMeasurementDao(),
            bodyMeasurementMapper = BodyMeasurementMapper(),
        )
    }

    @Test
    fun `every measurement survives the round trip`() =
        runBlocking {
            val id = repository.insert(FULL)

            assertEquals(FULL.copy(id = id), repository.getLatest().first())
        }

    @Test
    fun `an entry of weight alone is stored without the rest`() =
        runBlocking {
            val weighIn = BodyMeasurement(weight = 80_000_000, createdAt = 100, updatedAt = 100)

            val id = repository.insert(weighIn)

            assertEquals(weighIn.copy(id = id), repository.getLatest().first())
        }

    @Test
    fun `an entry with no weight in it is stored just as readily`() =
        runBlocking {
            // The other half of independent optionality: a user tracking a cut logs a waist
            // without stepping on the scale, and must not have to invent a weight to do it.
            val tapeOnly = BodyMeasurement(waist = 820, createdAt = 100, updatedAt = 100)

            val id = repository.insert(tapeOnly)

            assertEquals(tapeOnly.copy(id = id), repository.getLatest().first())
            assertNull(repository.getLatest().first()?.weight)
        }

    @Test
    fun `history comes back newest first`() =
        runBlocking {
            repository.insert(FULL.copy(createdAt = 100, updatedAt = 100))
            repository.insert(FULL.copy(createdAt = 300, updatedAt = 300))
            repository.insert(FULL.copy(createdAt = 200, updatedAt = 200))

            assertEquals(
                listOf(300L, 200L, 100L),
                repository.getAll().first().map { it.createdAt },
            )
        }

    @Test
    fun `the latest entry is the one taken most recently, not the one typed last`() =
        runBlocking {
            // `createdAt` is when the measurement was taken, so an entry logged this evening
            // for yesterday's weigh-in does not displace this morning's.
            repository.insert(FULL.copy(weight = 79_000_000, createdAt = 300, updatedAt = 300))
            repository.insert(FULL.copy(weight = 81_000_000, createdAt = 100, updatedAt = 400))

            assertEquals(79_000_000L, repository.getLatest().first()?.weight)
        }

    @Test
    fun `two entries taken at the same moment are ordered newest-written first`() =
        runBlocking {
            // A weigh-in and a set of circumferences logged for the same moment tie on
            // `created_at`, and "the latest entry" has to be the one written last rather than
            // whichever row the query plan reaches first.
            repository.insert(FULL.copy(weight = 81_000_000, createdAt = 100, updatedAt = 100))
            val second = repository.insert(FULL.copy(weight = 79_000_000, createdAt = 100, updatedAt = 100))

            assertEquals(second, repository.getLatest().first()?.id)
            assertEquals(second, repository.getAll().first().first().id)
        }

    @Test
    fun `latest is null before anything is logged`() =
        runBlocking {
            assertNull(repository.getLatest().first())
            assertTrue(repository.getAll().first().isEmpty())
        }

    @Test
    fun `an update clears a measurement the user removed`() =
        runBlocking {
            val id = repository.insert(FULL)

            repository.update(FULL.copy(id = id, waist = null, notes = null))

            val stored = repository.getLatest().first()
            assertNull(stored?.waist)
            assertNull(stored?.notes)
            assertEquals(FULL.chest, stored?.chest)
        }

    @Test
    fun `a deleted entry leaves the rest of the history alone`() =
        runBlocking {
            val kept = repository.insert(FULL.copy(createdAt = 100, updatedAt = 100))
            val removed = repository.insert(FULL.copy(createdAt = 200, updatedAt = 200))

            repository.delete(removed)

            assertEquals(listOf(kept), repository.getAll().first().map { it.id })
        }

    @Test
    fun `deleting an id nothing holds is not an error`() =
        runBlocking {
            repository.delete(404)

            assertTrue(repository.getAll().first().isEmpty())
        }
}
