package com.opengym.feature.history.exercise

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.ExerciseType
import com.opengym.core.domain.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draft's own rules, as the pure functions they are.
 *
 * No ViewModel, no repository, no coroutine. What row a draft describes, what an edit is
 * allowed to overwrite and whether a name collides are decisions, and a decision is worth
 * asserting on directly rather than through four layers of Flow.
 */
class ExerciseDraftTest {

    private val chest = MuscleGroup(id = 1, name = "CHEST")
    private val triceps = MuscleGroup(id = 2, name = "TRICEPS")
    private val lats = MuscleGroup(id = 3, name = "LATS")

    // ─── Creating ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a draft becomes a custom exercise with its notes trimmed`() {
        val exercise = ExerciseDraft(
            name = "  Hammer incline press  ",
            equipment = EquipmentType.MACHINE,
            notes = "  reads 5kg light  ",
        ).toExercise()

        assertEquals("Hammer incline press", exercise.name)
        assertEquals(EquipmentType.MACHINE, exercise.equipmentType)
        assertEquals("reads 5kg light", exercise.notes)
        assertEquals(true, exercise.isCustom)
        assertNull(exercise.icon)
    }

    @Test
    fun `empty notes are stored as absent rather than as an empty string`() {
        assertNull(ExerciseDraft(name = "Dip", notes = "   ").toExercise().notes)
    }

    // ─── Editing ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a draft of an existing exercise carries its id and its links`() {
        val draft = exerciseDraftOf(
            exercise = Exercise(
                id = 7,
                name = "Hammer press",
                type = ExerciseType.STRENGTH,
                equipmentType = EquipmentType.MACHINE,
                notes = "Seat on 3.",
                isCustom = true,
            ),
            links = listOf(
                ExerciseMuscleGroupLink(chest, isPrimary = true),
                ExerciseMuscleGroupLink(triceps, isPrimary = false),
            ),
        )

        assertEquals(7L, draft.id)
        assertFalse(draft.isNew)
        assertEquals("Hammer press", draft.name)
        assertEquals(EquipmentType.MACHINE, draft.equipment)
        assertEquals(chest.id, draft.primaryMuscleGroupId)
        assertEquals(setOf(triceps.id), draft.secondaryMuscleGroupIds)
        assertEquals("Seat on 3.", draft.notes)
    }

    @Test
    fun `an exercise with no recorded equipment opens on one the form can show`() {
        // OTHER has no chip, by design, and null is what a row written before the column
        // existed carries. Either way the form has to preselect something real.
        val draft = exerciseDraftOf(Exercise(id = 7, name = "Sled push"), links = emptyList())

        assertEquals(EquipmentType.BODYWEIGHT, draft.equipment)
    }

    @Test
    fun `applying a draft keeps the fields the form never asked about`() {
        val stored = Exercise(
            id = 7,
            name = "Hammer press",
            equipmentType = EquipmentType.MACHINE,
            defaultRest = 90,
            instructions = "Sit down. Press.",
            isUnilateral = true,
            isCustom = true,
            icon = "seed/images/Hammer_Press.webp",
        )

        val edited = exerciseDraftOf(stored, links = emptyList())
            .copy(name = "  Hammer incline press  ", notes = "  Seat 2  ")
            .applyTo(stored)

        assertEquals("Hammer incline press", edited.name)
        assertEquals("Seat 2", edited.notes)
        // Everything the form has no control for survives — this is the whole difference
        // between an edit and a silent reset.
        assertEquals(7L, edited.id)
        assertEquals(90L, edited.defaultRest)
        assertEquals("Sit down. Press.", edited.instructions)
        assertTrue(edited.isUnilateral)
        assertTrue(edited.isCustom)
        assertEquals("seed/images/Hammer_Press.webp", edited.icon)
    }

    @Test
    fun `clearing the note on an edit clears the column`() {
        val stored = Exercise(id = 7, name = "Hammer press", notes = "Seat on 3.")

        assertNull(exerciseDraftOf(stored, emptyList()).copy(notes = "  ").applyTo(stored).notes)
    }

    // ─── Muscle links ───────────────────────────────────────────────────────────────────

    @Test
    fun `resolving muscle groups puts the prime mover first`() {
        val links = ExerciseDraft(
            name = "Dip",
            primaryMuscleGroupId = chest.id,
            secondaryMuscleGroupIds = setOf(triceps.id),
        ).resolveMuscleGroups(listOf(triceps, chest, lats))

        assertEquals(listOf(chest, triceps), links.map { it.muscleGroup })
        assertEquals(listOf(true, false), links.map { it.isPrimary })
    }

    @Test
    fun `a group selected as both yields one link, the primary`() {
        val links = ExerciseDraft(
            name = "Dip",
            primaryMuscleGroupId = chest.id,
            secondaryMuscleGroupIds = setOf(chest.id),
        ).resolveMuscleGroups(listOf(chest))

        // Two links with the same key would be rejected by the join table's primary key,
        // which is a save that fails for a reason the user cannot see.
        assertEquals(listOf(chest), links.map { it.muscleGroup })
        assertEquals(listOf(true), links.map { it.isPrimary })
    }

    @Test
    fun `a group that has vanished from the taxonomy is dropped rather than fabricated`() {
        val links = ExerciseDraft(
            name = "Dip",
            primaryMuscleGroupId = chest.id,
            secondaryMuscleGroupIds = setOf(GONE_MUSCLE_GROUP_ID),
        ).resolveMuscleGroups(listOf(chest))

        assertEquals(listOf(chest), links.map { it.muscleGroup })
    }

    // ─── Duplicate names ────────────────────────────────────────────────────────────────

    private val catalogue = listOf(
        Exercise(id = 10, name = "Bench press", equipmentType = EquipmentType.BARBELL),
        Exercise(id = 11, name = "Bench press", equipmentType = EquipmentType.DUMBBELL),
    )

    @Test
    fun `a name is a duplicate only for the same equipment`() {
        val barbell = ExerciseDraft(name = "bench press", equipment = EquipmentType.BARBELL)
        val cable = ExerciseDraft(name = "bench press", equipment = EquipmentType.CABLE)

        // Case-insensitively: the index is not what the user is trying to satisfy, and
        // "bench press" beside "Bench press" is a duplicate to anyone reading the list.
        assertTrue(barbell.isDuplicateIn(catalogue))
        assertFalse(cable.isDuplicateIn(catalogue))
    }

    @Test
    fun `negative - an exercise is not a duplicate of itself`() {
        val editing = ExerciseDraft(
            id = 10,
            name = "Bench press",
            equipment = EquipmentType.BARBELL,
        )

        assertFalse(editing.isDuplicateIn(catalogue))
    }

    @Test
    fun `negative - an empty name is not a duplicate of anything`() {
        assertFalse(ExerciseDraft(name = "   ").isDuplicateIn(catalogue))
    }

    // ─── Saving ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a draft is saveable once it has a name, a prime mover and no collision`() {
        val complete = ExerciseDraft(name = "Dip", primaryMuscleGroupId = chest.id)

        assertTrue(complete.canSave)
        assertFalse(complete.copy(name = " ").canSave)
        assertFalse(complete.copy(primaryMuscleGroupId = null).canSave)
        assertFalse(complete.copy(isDuplicateName = true).canSave)
        assertFalse(complete.copy(isSaving = true).canSave)
    }

    private companion object {
        /** An id no taxonomy holds — a group deleted between the form opening and the save. */
        const val GONE_MUSCLE_GROUP_ID = 404L
    }
}
