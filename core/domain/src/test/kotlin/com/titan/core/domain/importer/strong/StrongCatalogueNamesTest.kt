package com.titan.core.domain.importer.strong

import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.ImporterFixtures
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strong's mapping table, held to the catalogue that ships rather than to a copy of it: every
 * entry has to land, through the same matcher an import uses, on exactly the row it names.
 */
class StrongCatalogueNamesTest {

    private val names = SuffixedExerciseNames(STRONG_CATALOGUE_NAMES)
    private val matcher = ExerciseMatcher(ImporterFixtures.catalogue)
    private val byName = ImporterFixtures.catalogue.associateBy { it.name }

    @Test
    fun `every entry resolves to the catalogue exercise it names, equipment included`() {
        val misses = STRONG_CATALOGUE_NAMES.mapNotNull { (strong, target) ->
            val row = byName[target] ?: return@mapNotNull "$strong: the catalogue has no \"$target\""
            val found = matcher.match(names.exercise(strong), row.type)
            if (found == row) null else "$strong: resolved to ${found?.name}, not $target"
        }

        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    @Test
    fun `no two entries are one name once normalised, so none is shadowed`() {
        val keys = STRONG_CATALOGUE_NAMES.keys.map(ExerciseMatcher::normalised)

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a name the table leaves out still matches a catalogue row with its name and equipment`() {
        val found = matcher.match(names.exercise("Romanian Deadlift (Barbell)"), ExerciseType.STRENGTH)

        assertEquals("Romanian Deadlift", found?.name)
    }

    @Test
    fun `equipment that differs keeps a lift off another lift's row`() {
        // The catalogue's preacher curl is a barbell exercise.
        assertNull(matcher.match(names.exercise("Preacher Curl (Dumbbell)"), ExerciseType.STRENGTH))
    }

    @Test
    fun `a variation is not merged into the plain movement`() {
        assertNull(matcher.match(names.exercise("Pull Up (Assisted)"), ExerciseType.STRENGTH))
        assertNull(matcher.match(names.exercise("Push Up (Knees)"), ExerciseType.STRENGTH))
    }
}
