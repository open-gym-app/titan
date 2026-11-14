package com.titan.core.data.mapper

import com.titan.core.data.db.entity.GymEntity
import com.titan.core.domain.model.Coordinates
import com.titan.core.domain.model.Gym
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GymMapperTest {

    private val mapper = GymMapper()

    private val gym = Gym(
        id = 7,
        name = "Iron Works",
        address = "12 Forge Street",
        coordinates = Coordinates(51.507400, -0.127800),
        equipmentTags = listOf("squat rack", "dumbbells to 50 kg"),
        userNotes = "closes at 22:00",
        createdAt = 1_700_000_000_000,
    )

    @Test
    fun `round trips every field`() {
        assertEquals(gym, mapper.toDomain(mapper.toEntity(gym)))
    }

    @Test
    fun `coordinates are stored as integer microdegrees`() {
        val entity = mapper.toEntity(gym)

        assertEquals(51_507_400, entity.latitudeMicro)
        assertEquals(-127_800, entity.longitudeMicro)
    }

    @Test
    fun `coordinates are rounded to the nearest microdegree, not truncated`() {
        val entity = mapper.toEntity(gym.copy(coordinates = Coordinates(10.0000006, -10.0000006)))

        assertEquals(10_000_001, entity.latitudeMicro)
        assertEquals(-10_000_001, entity.longitudeMicro)
    }

    @Test
    fun `the extreme coordinates fit the column`() {
        val corner = mapper.toDomain(mapper.toEntity(gym.copy(coordinates = Coordinates(-90.0, 180.0))))

        assertEquals(Coordinates(-90.0, 180.0), corner.coordinates)
    }

    @Test
    fun `tags are stored one per line, in order`() {
        assertEquals("squat rack\ndumbbells to 50 kg", mapper.toEntity(gym).equipmentTags)
    }

    @Test
    fun `no tags is the empty string and reads back as an empty list`() {
        val entity = mapper.toEntity(gym.copy(equipmentTags = emptyList()))

        assertEquals("", entity.equipmentTags)
        assertEquals(emptyList<String>(), mapper.toDomain(entity).equipmentTags)
    }

    @Test
    fun `absent notes stay null`() {
        val entity: GymEntity = mapper.toEntity(gym.copy(userNotes = null))

        assertNull(entity.userNotes)
        assertNull(mapper.toDomain(entity).userNotes)
    }
}
