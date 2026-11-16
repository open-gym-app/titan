package com.titan.core.domain.importer.fitnotes

import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.ImporterFixtures
import com.titan.core.domain.importer.SUFFIXED_CATALOGUE_NAMES
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FitNotes' mapping table, held to the catalogue that ships rather than to a copy of it: every
 * entry has to land, through the same matcher an import uses, on exactly the row it names.
 */
class FitNotesCatalogueNamesTest {

    private val names = SuffixedExerciseNames(FITNOTES_CATALOGUE_NAMES)
    private val matcher = ExerciseMatcher(ImporterFixtures.catalogue)
    private val byName = ImporterFixtures.catalogue.associateBy { it.name }

    @Test
    fun `every entry resolves to the catalogue exercise it names`() {
        val misses = FITNOTES_CATALOGUE_NAMES.mapNotNull { (fitNotes, target) ->
            val row = byName[target] ?: return@mapNotNull "$fitNotes: the catalogue has no \"$target\""
            val found = matcher.match(names.exercise(fitNotes), row.type)
            if (found == row) null else "$fitNotes: resolved to ${found?.name}, not $target"
        }

        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    @Test
    fun `the library names the catalogue already spells the same way need no entry`() {
        mapOf(
            "Barbell Squat" to "Barbell Squat",
            "Chin Up" to "Chin-Up",
            "Close Grip Barbell Bench Press" to "Close-Grip Barbell Bench Press",
            "Glute-Ham Raise" to "Glute Ham Raise",
            "Romanian Deadlift" to "Romanian Deadlift",
            "Elliptical Trainer" to "Elliptical Trainer",
            "Running (Treadmill)" to "Running, Treadmill",
        ).forEach { (fitNotes, target) ->
            val row = byName.getValue(target)
            assertNull(fitNotes, FITNOTES_CATALOGUE_NAMES[fitNotes])
            assertEquals(fitNotes, row, matcher.match(names.exercise(fitNotes), row.type))
        }
    }

    @Test
    fun `an entry that is also a Strong and Hevy name names the same row, and none is listed twice`() {
        val shared = SUFFIXED_CATALOGUE_NAMES.mapKeys { (name, _) -> ExerciseMatcher.normalised(name) }
        val own = FITNOTES_CATALOGUE_NAMES.mapKeys { (name, _) -> ExerciseMatcher.normalised(name) }

        val conflicts = own.filter { (name, target) -> shared[name] != null && shared[name] != target }
        assertTrue(conflicts.toString(), conflicts.isEmpty())
        assertTrue(own.keys.any(shared::containsKey))
        assertEquals(FITNOTES_CATALOGUE_NAMES.size, own.size)
    }

    @Test
    fun `the near misses stay custom`() {
        // A plate-loaded machine, a name that could be two lifts, and a timed plank.
        assertNull(matcher.match(names.exercise("Hammer Strength Shoulder Press"), ExerciseType.STRENGTH))
        assertNull(matcher.match(names.exercise("Lying Triceps Extension"), ExerciseType.STRENGTH))
        assertNull(matcher.match(names.exercise("Plank"), ExerciseType.ISOMETRIC))
        assertNull(matcher.match(names.exercise("Running (Outdoor)"), ExerciseType.ENDURANCE))
    }
}
