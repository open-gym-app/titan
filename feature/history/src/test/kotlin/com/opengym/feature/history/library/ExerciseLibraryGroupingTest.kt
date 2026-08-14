package com.opengym.feature.history.library

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.PrType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The library's rules, tested as the pure functions they are.
 *
 * No ViewModel, no repository, no coroutine: what an intersection of two filters means and
 * which section an exercise belongs in are decisions, and a decision is worth asserting on
 * directly rather than through four layers of Flow.
 */
class ExerciseLibraryGroupingTest {

    private val chest = MuscleGroup(id = 1, name = "CHEST")
    private val triceps = MuscleGroup(id = 2, name = "TRICEPS")
    private val lats = MuscleGroup(id = 3, name = "LATS")

    private val bench = Exercise(id = 10, name = "Bench press", equipmentType = EquipmentType.BARBELL)
    private val fly = Exercise(id = 11, name = "Cable fly", equipmentType = EquipmentType.CABLE)
    private val pullUp = Exercise(id = 12, name = "Pull-up", equipmentType = EquipmentType.BODYWEIGHT)
    private val carry = Exercise(id = 13, name = "Farmer's carry", equipmentType = EquipmentType.DUMBBELL)

    private val links = mapOf(
        bench.id to listOf(chest.primary(), triceps.secondary()),
        fly.id to listOf(chest.primary()),
        pullUp.id to listOf(lats.primary()),
        // `carry` is deliberately absent: the seed leaves a handful of exercises unlinked.
    )

    private val all = listOf(bench, fly, pullUp, carry)

    private fun MuscleGroup.primary() = ExerciseMuscleGroupLink(this, isPrimary = true)
    private fun MuscleGroup.secondary() = ExerciseMuscleGroupLink(this, isPrimary = false)

    // ─── Filtering ──────────────────────────────────────────────────────────────────────

    @Test
    fun `no filters keeps everything`() {
        assertEquals(all, all.filterBy(muscleGroupId = null, equipment = emptySet(), links = links))
    }

    @Test
    fun `a muscle filter matches secondary movers too`() {
        val result = all.filterBy(muscleGroupId = triceps.id, equipment = emptySet(), links = links)

        // The bench press works triceps without being a triceps exercise, and a lifter
        // looking for triceps work should be shown it.
        assertEquals(listOf(bench), result)
    }

    @Test
    fun `an equipment filter is a union within itself`() {
        val result = all.filterBy(
            muscleGroupId = null,
            equipment = setOf(EquipmentType.BARBELL, EquipmentType.CABLE),
            links = links,
        )

        assertEquals(listOf(bench, fly), result)
    }

    @Test
    fun `the two filters intersect`() {
        val result = all.filterBy(
            muscleGroupId = chest.id,
            equipment = setOf(EquipmentType.CABLE),
            links = links,
        )

        // Chest alone would also return the bench press; cable alone would return only the
        // fly. Together they are the one exercise that is both.
        assertEquals(listOf(fly), result)
    }

    @Test
    fun `an intersection with nothing in it is empty rather than either half`() {
        val result = all.filterBy(
            muscleGroupId = lats.id,
            equipment = setOf(EquipmentType.CABLE),
            links = links,
        )

        assertEquals(emptyList<Exercise>(), result)
    }

    @Test
    fun `an exercise the catalogue links to nothing survives an empty muscle filter only`() {
        assertEquals(
            emptyList<Exercise>(),
            listOf(carry).filterBy(muscleGroupId = chest.id, equipment = emptySet(), links = links),
        )
        assertEquals(
            listOf(carry),
            listOf(carry).filterBy(muscleGroupId = null, equipment = emptySet(), links = links),
        )
    }

    // ─── Grouping ───────────────────────────────────────────────────────────────────────

    @Test
    fun `sections group on the prime mover and are ordered by muscle group name`() {
        val sections = all.groupIntoSections(links, records = emptyMap())

        assertEquals(
            listOf("CHEST", "LATS", null),
            sections.map { it.muscleGroup?.name },
        )
        assertEquals(listOf(bench, fly), sections.first().exercises.map { it.exercise })
    }

    @Test
    fun `an exercise appears once, under what it trains most`() {
        val sections = all.groupIntoSections(links, records = emptyMap())

        // The bench press works the triceps, and there is no TRICEPS section: a grouped list
        // files each row once.
        val benchSections = sections.filter { section ->
            section.exercises.any { it.exercise == bench }
        }
        assertEquals(listOf("CHEST"), benchSections.map { it.muscleGroup?.name })
    }

    @Test
    fun `unlinked exercises land in the null section, last`() {
        val sections = all.groupIntoSections(links, records = emptyMap())

        val unlinked = sections.last()
        assertNull(unlinked.muscleGroup)
        assertEquals(listOf(carry), unlinked.exercises.map { it.exercise })
    }

    @Test
    fun `an exercise with no primary link is filed under its first secondary`() {
        val onlySecondary = mapOf(carry.id to listOf(triceps.secondary()))

        val sections = listOf(carry).groupIntoSections(onlySecondary, records = emptyMap())

        assertEquals(listOf("TRICEPS"), sections.map { it.muscleGroup?.name })
    }

    @Test
    fun `a row carries its own record and its own links`() {
        val record = PersonalRecord(
            id = 1,
            exerciseId = bench.id,
            workoutSetId = 1,
            type = PrType.MAX_WEIGHT,
            weight = 92_000_000,
            achievedAt = 0,
        )

        val sections = all.groupIntoSections(links, records = mapOf(bench.id to record))
        val rows = sections.first().exercises

        assertEquals(record, rows.first { it.exercise == bench }.record)
        assertNull(rows.first { it.exercise == fly }.record)
        assertEquals(listOf(chest, triceps), rows.first().muscleGroups.map { it.muscleGroup })
    }

    // ─── The equipment vocabulary ───────────────────────────────────────────────────────

    @Test
    fun `the equipment vocabulary is what the catalogue holds, in enum order`() {
        assertEquals(
            listOf(EquipmentType.BARBELL, EquipmentType.DUMBBELL, EquipmentType.BODYWEIGHT, EquipmentType.CABLE),
            all.equipmentVocabulary(),
        )
    }

    @Test
    fun `neither unrecorded nor unnameable equipment gets a chip`() {
        val exercises = listOf(
            bench,
            Exercise(id = 20, name = "Sled push", equipmentType = EquipmentType.OTHER),
            Exercise(id = 21, name = "Mystery machine", equipmentType = null),
        )

        assertEquals(listOf(EquipmentType.BARBELL), exercises.equipmentVocabulary())
    }

    // ─── The draft ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a draft becomes a custom exercise with its notes trimmed`() {
        val exercise = NewExerciseDraft(
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
        assertNull(NewExerciseDraft(name = "Dip", notes = "   ").toExercise().notes)
    }

    @Test
    fun `resolving muscle groups puts the prime mover first`() {
        val links = NewExerciseDraft(
            name = "Dip",
            primaryMuscleGroupId = chest.id,
            secondaryMuscleGroupIds = setOf(triceps.id),
        ).resolveMuscleGroups(listOf(triceps, chest, lats))

        assertEquals(listOf(chest, triceps), links.map { it.muscleGroup })
        assertEquals(listOf(true, false), links.map { it.isPrimary })
    }

    @Test
    fun `a group selected as both yields one link, the primary`() {
        val links = NewExerciseDraft(
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
        val links = NewExerciseDraft(
            name = "Dip",
            primaryMuscleGroupId = chest.id,
            secondaryMuscleGroupIds = setOf(GONE_MUSCLE_GROUP_ID),
        ).resolveMuscleGroups(listOf(chest))

        assertEquals(listOf(chest), links.map { it.muscleGroup })
    }

    private companion object {
        /** An id no taxonomy holds — a group deleted between the form opening and the save. */
        const val GONE_MUSCLE_GROUP_ID = 404L
    }
}
