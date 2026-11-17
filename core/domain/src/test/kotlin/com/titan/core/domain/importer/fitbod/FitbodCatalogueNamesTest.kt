package com.titan.core.domain.importer.fitbod

import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.ImporterFixtures
import com.titan.core.domain.importer.SUFFIXED_CATALOGUE_NAMES
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.importer.fitnotes.FITNOTES_CATALOGUE_NAMES
import com.titan.core.domain.importer.jefit.JEFIT_CATALOGUE_NAMES
import com.titan.core.domain.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fitbod's mapping table, held to the catalogue that ships rather than to a copy of it: every
 * entry has to land, through the same names reader and matcher an import uses, on exactly the row
 * it names.
 */
class FitbodCatalogueNamesTest {

    private val names = SuffixedExerciseNames(FITBOD_CATALOGUE_NAMES, leadingEquipment = true)
    private val matcher = ExerciseMatcher(ImporterFixtures.catalogue)
    private val byName = ImporterFixtures.catalogue.associateBy { it.name }

    @Test
    fun `every entry resolves to the catalogue exercise it names`() {
        val misses = FITBOD_CATALOGUE_NAMES.mapNotNull { (fitbod, target) ->
            val row = byName[target] ?: return@mapNotNull "$fitbod: the catalogue has no \"$target\""
            val found = matcher.match(names.exercise(fitbod), row.type)
            if (found == row) null else "$fitbod: resolved to ${found?.name}, not $target"
        }

        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    @Test
    fun `the library names the catalogue already spells the same way need no entry`() {
        mapOf(
            "Barbell Curl" to "Barbell Curl",
            "Barbell Hip Thrust" to "Barbell Hip Thrust",
            "Romanian Deadlift" to "Romanian Deadlift",
            "Dumbbell Bench Press" to "Dumbbell Bench Press",
            "Hammer Curls" to "Hammer Curls",
            "EZ-Bar Curl" to "EZ-Bar Curl",
            "Wide Grip Lat Pulldown" to "Wide-Grip Lat Pulldown",
            "Chin Up" to "Chin-Up",
            "Single Arm Cable Crossover" to "Single-Arm Cable Crossover",
            "Running - Treadmill" to "Running, Treadmill",
        ).forEach { (fitbod, target) ->
            val row = byName.getValue(target)
            assertNull(fitbod, FITBOD_CATALOGUE_NAMES[fitbod])
            assertEquals(fitbod, row, matcher.match(names.exercise(fitbod), row.type))
        }
    }

    @Test
    fun `an entry that is also another source's name names the same row, and none is listed twice`() {
        val own = FITBOD_CATALOGUE_NAMES.mapKeys { (name, _) -> ExerciseMatcher.normalised(name) }

        listOf(SUFFIXED_CATALOGUE_NAMES, FITNOTES_CATALOGUE_NAMES, JEFIT_CATALOGUE_NAMES).forEach { table ->
            val other = table.mapKeys { (name, _) -> ExerciseMatcher.normalised(name) }
            val conflicts = own.filter { (name, target) -> other[name] != null && other[name] != target }
            assertTrue(conflicts.toString(), conflicts.isEmpty())
        }
        assertEquals(FITBOD_CATALOGUE_NAMES.size, own.size)
    }

    @Test
    fun `the near misses stay custom`() {
        // A name beside a more specific one, a standing raise the catalogue only has seated,
        // a dumbbell lift the catalogue only has with a kettlebell, and a timed plank.
        assertNull(matcher.match(names.exercise("Lat Pulldown"), ExerciseType.STRENGTH))
        assertNull(matcher.match(names.exercise("Hip Thrust"), ExerciseType.STRENGTH))
        assertNull(matcher.match(names.exercise("Cable Lateral Raise"), ExerciseType.STRENGTH))
        assertNull(matcher.match(names.exercise("Dumbbell Goblet Squat"), ExerciseType.STRENGTH))
        assertNull(matcher.match(names.exercise("Plank"), ExerciseType.ISOMETRIC))
        assertNull(matcher.match(names.exercise("Rowing"), ExerciseType.ENDURANCE))
    }
}
