package com.titan.core.domain.importer.hevy

import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.ImporterFixtures
import com.titan.core.domain.importer.SUFFIXED_CATALOGUE_NAMES
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hevy's mapping table, held to the catalogue that ships rather than to a copy of it: every
 * entry, shared with Strong or Hevy's own, has to land, through the same matcher an import uses,
 * on exactly the row it names.
 */
class HevyCatalogueNamesTest {

    private val names = SuffixedExerciseNames(HEVY_CATALOGUE_NAMES)
    private val matcher = ExerciseMatcher(ImporterFixtures.catalogue)
    private val byName = ImporterFixtures.catalogue.associateBy { it.name }

    @Test
    fun `every entry resolves to the catalogue exercise it names, equipment included`() {
        val misses = HEVY_CATALOGUE_NAMES.mapNotNull { (hevy, target) ->
            val row = byName[target] ?: return@mapNotNull "$hevy: the catalogue has no \"$target\""
            val found = matcher.match(names.exercise(hevy), row.type)
            if (found == row) null else "$hevy: resolved to ${found?.name}, not $target"
        }

        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    @Test
    fun `no entry of Hevy's own is a shared name, so none overrides one, and none is shadowed`() {
        val shared = SUFFIXED_CATALOGUE_NAMES.keys.map(ExerciseMatcher::normalised).toSet()
        val own = (HEVY_CATALOGUE_NAMES.keys - SUFFIXED_CATALOGUE_NAMES.keys).map(ExerciseMatcher::normalised)
        val all = HEVY_CATALOGUE_NAMES.keys.map(ExerciseMatcher::normalised)

        assertTrue(own.filter { it in shared }.toString(), own.none { it in shared })
        assertEquals(SUFFIXED_CATALOGUE_NAMES.size + own.size, HEVY_CATALOGUE_NAMES.size)
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `Hevy's own spelling of the Smith machine still names the machine`() {
        val found = matcher.match(names.exercise("Standing Calf Raise (Smith)"), ExerciseType.STRENGTH)

        assertEquals("Smith Machine Calf Raise", found?.name)
        assertEquals(EquipmentType.MACHINE, names.exercise("Standing Calf Raise (Smith)").equipment)
    }

    @Test
    fun `a translated name is not mapped, and matches nothing by accident`() {
        assertNull(matcher.match(names.exercise("Bankdrücken (Langhantel)"), ExerciseType.STRENGTH))
    }

    @Test
    fun `the near misses stay custom`() {
        // The catalogue's barbell skullcrusher is an EZ-bar one, and its preacher curl a barbell one.
        assertNull(matcher.match(names.exercise("Skullcrusher (Barbell)"), ExerciseType.STRENGTH))
        assertNull(matcher.match(names.exercise("Preacher Curl (Dumbbell)"), ExerciseType.STRENGTH))
        assertNull(matcher.match(names.exercise("Iso-Lateral Chest Press (Machine)"), ExerciseType.STRENGTH))
    }
}
