package com.titan.core.domain.importer.jefit

import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.ImporterFixtures
import com.titan.core.domain.importer.SUFFIXED_CATALOGUE_NAMES
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.importer.fitnotes.FITNOTES_CATALOGUE_NAMES
import com.titan.core.domain.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JEFIT's mapping table, held to the catalogue that ships rather than to a copy of it: every
 * entry has to land, through the same names reader and matcher an import uses, on exactly the row
 * it names.
 */
class JefitCatalogueNamesTest {

    private val names = SuffixedExerciseNames(JEFIT_CATALOGUE_NAMES, leadingEquipment = true)
    private val matcher = ExerciseMatcher(ImporterFixtures.catalogue)
    private val byName = ImporterFixtures.catalogue.associateBy { it.name }

    @Test
    fun `every entry resolves to the catalogue exercise it names`() {
        val misses = JEFIT_CATALOGUE_NAMES.mapNotNull { (jefit, target) ->
            val row = byName[target] ?: return@mapNotNull "$jefit: the catalogue has no \"$target\""
            val found = matcher.match(names.exercise(jefit), row.type)
            if (found == row) null else "$jefit: resolved to ${found?.name}, not $target"
        }

        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    @Test
    fun `the library names the catalogue already spells the same way need no entry`() {
        mapOf(
            "Barbell Squat" to "Barbell Squat",
            "Barbell Deadlift" to "Barbell Deadlift",
            "Barbell Hip Thrust" to "Barbell Hip Thrust",
            "Dumbbell Bench Press" to "Dumbbell Bench Press",
            "EZ Bar Curl" to "EZ-Bar Curl",
            "Wide Grip Lat Pulldown" to "Wide-Grip Lat Pulldown",
            "Lying Leg Curls" to "Lying Leg Curls",
            "Cable Crunch" to "Cable Crunch",
            "Trap Bar Deadlift" to "Trap Bar Deadlift",
        ).forEach { (jefit, target) ->
            val row = byName.getValue(target)
            assertNull(jefit, JEFIT_CATALOGUE_NAMES[jefit])
            assertEquals(jefit, row, matcher.match(names.exercise(jefit), row.type))
        }
    }

    @Test
    fun `an entry that is also another source's name names the same row, and none is listed twice`() {
        val own = JEFIT_CATALOGUE_NAMES.mapKeys { (name, _) -> ExerciseMatcher.normalised(name) }

        listOf(SUFFIXED_CATALOGUE_NAMES, FITNOTES_CATALOGUE_NAMES).forEach { table ->
            val other = table.mapKeys { (name, _) -> ExerciseMatcher.normalised(name) }
            val conflicts = own.filter { (name, target) -> other[name] != null && other[name] != target }
            assertTrue(conflicts.toString(), conflicts.isEmpty())
        }
        assertEquals(JEFIT_CATALOGUE_NAMES.size, own.size)
    }

    @Test
    fun `the near misses stay custom`() {
        // A standing raise the catalogue only has seated, a name that could be two lifts, a timed plank.
        assertNull(matcher.match(names.exercise("Cable Lateral Raise"), ExerciseType.STRENGTH))
        assertNull(matcher.match(names.exercise("Dip"), ExerciseType.STRENGTH))
        assertNull(matcher.match(names.exercise("Plank"), ExerciseType.ISOMETRIC))
        assertNull(matcher.match(names.exercise("Running"), ExerciseType.ENDURANCE))
    }

    @Test
    fun `no catalogue name starts with one piece of equipment and is filed under another`() {
        // What makes reading the equipment off the front of a JEFIT name safe: a JEFIT name the
        // catalogue spells the same way can never be refused for the equipment its name says.
        val disagreements = ImporterFixtures.catalogue.filter { row ->
            SuffixedExerciseNames.leadingEquipmentOf(row.name)?.let { it != row.equipmentType } == true
        }

        assertTrue(disagreements.joinToString { "${it.name} (${it.equipmentType})" }, disagreements.isEmpty())
    }
}
