package com.opengym.core.data.mapper

import com.opengym.core.data.db.entity.MuscleGroupEntity
import com.opengym.core.data.db.entity.TagEntity
import com.opengym.core.data.db.projection.ExerciseMuscleGroupLinkRow
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.SessionExercise
import com.opengym.core.domain.model.Tag
import com.opengym.core.domain.model.WorkoutSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mappers with no decisions to make: a field-for-field copy either survives a
 * round trip or a column has been added on one side only, which is the whole of
 * what these assert.
 */
class SessionMappersTest {

    @Test
    fun `session round trips every field`() {
        val session = WorkoutSession(
            id = 5,
            templateId = 2,
            name = "Push Day - Upper",
            completedAt = 1_700_000_100_000,
            isInProgress = false,
            lastActiveAt = 1_700_000_090_000,
            notes = "felt heavy",
            createdAt = 1_700_000_000_000,
            updatedAt = 1_700_000_100_000,
        )
        val mapper = SessionMapper()

        assertEquals(session, mapper.toDomain(mapper.toEntity(session)))
    }

    @Test
    fun `an unnamed session round trips as unnamed`() {
        // The null carries meaning of its own — see WorkoutSession.name — so a mapper
        // that defaulted it to "" would be losing a field, not normalising one.
        val session = WorkoutSession(
            id = 6,
            name = null,
            lastActiveAt = 1_700_000_000_000,
            createdAt = 1_700_000_000_000,
            updatedAt = 1_700_000_000_000,
        )
        val mapper = SessionMapper()

        assertNull(mapper.toEntity(session).name)
        assertEquals(session, mapper.toDomain(mapper.toEntity(session)))
    }

    @Test
    fun `session exercise round trips every field`() {
        val sessionExercise = SessionExercise(
            id = 9,
            sessionId = 5,
            exerciseId = 3,
            position = 2,
            supersetGroup = 1,
            templateExerciseId = 12,
            notes = "swapped for the safety bar",
            createdAt = 1_700_000_000_000,
            updatedAt = 1_700_000_000_001,
        )
        val mapper = SessionExerciseMapper()

        assertEquals(sessionExercise, mapper.toDomain(mapper.toEntity(sessionExercise)))
    }

    @Test
    fun `a standalone session exercise round trips outside any superset`() {
        // NULL is what "not part of a superset" is stored as, so a mapper that turned
        // it into a group id — or a group id into NULL — would silently regroup a
        // workout. See Supersets for what a group means.
        val sessionExercise = SessionExercise(
            id = 10,
            sessionId = 5,
            exerciseId = 3,
            position = 0,
            supersetGroup = null,
            createdAt = 1_700_000_000_000,
            updatedAt = 1_700_000_000_000,
        )
        val mapper = SessionExerciseMapper()

        assertNull(mapper.toEntity(sessionExercise).supersetGroup)
        assertEquals(sessionExercise, mapper.toDomain(mapper.toEntity(sessionExercise)))
    }

    @Test
    fun `tag round trips every field`() {
        val tag = Tag(id = 4, name = "PUSH", color = "#FF0000", isSystem = true)
        val mapper = TagMapper()

        assertEquals(tag, mapper.toDomain(mapper.toEntity(tag)))
    }

    @Test
    fun `muscle group maps from its entity`() {
        val mapped = MuscleGroupLinkMapper().toDomain(MuscleGroupEntity(id = 6, name = "CHEST", icon = "chest"))

        assertEquals(MuscleGroup(id = 6, name = "CHEST", icon = "chest"), mapped)
    }

    @Test
    fun `a link carries the join row's primary flag`() {
        val mapper = MuscleGroupLinkMapper()
        val row = ExerciseMuscleGroupLinkRow(
            muscleGroupId = 6,
            muscleGroupName = "TRICEPS",
            muscleGroupIcon = null,
            isPrimary = false,
        )

        val link = mapper.toDomain(row)

        assertEquals(MuscleGroup(id = 6, name = "TRICEPS"), link.muscleGroup)
        assertFalse(link.isPrimary)
        assertTrue(mapper.toDomain(row.copy(isPrimary = true)).isPrimary)
    }

    @Test
    fun `a system tag stays a system tag through the entity`() {
        val mapper = TagMapper()
        val entity: TagEntity = mapper.toEntity(Tag(name = "SYSTEM", isSystem = true))

        assertTrue(entity.isSystem)
        assertTrue(mapper.toDomain(entity).isSystem)
    }
}
