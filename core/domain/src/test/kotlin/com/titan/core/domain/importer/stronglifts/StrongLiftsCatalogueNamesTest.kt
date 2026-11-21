package com.titan.core.domain.importer.stronglifts

import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.ImporterFixtures
import com.titan.core.domain.importer.SUFFIXED_CATALOGUE_NAMES
import com.titan.core.domain.importer.fitbod.FITBOD_CATALOGUE_NAMES
import com.titan.core.domain.importer.fitnotes.FITNOTES_CATALOGUE_NAMES
import com.titan.core.domain.importer.jefit.JEFIT_CATALOGUE_NAMES
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StrongLifts' mapping table, held to the catalogue that ships rather than to a copy of it: every
 * entry has to land, through the same exercises an import builds and the matcher it uses, on
 * exactly the row it names.
 */
class StrongLiftsCatalogueNamesTest {

    private val history = StrongLiftsHistory(ImporterFixtures.METRIC_UTC)
    private val matcher = ExerciseMatcher(ImporterFixtures.catalogue)
    private val byName = ImporterFixtures.catalogue.associateBy { it.name }

    @Test
    fun `every entry resolves to the catalogue exercise it names`() {
        val misses = STRONGLIFTS_CATALOGUE_NAMES.mapNotNull { (stronglifts, target) ->
            val row = byName[target] ?: return@mapNotNull "$stronglifts: the catalogue has no \"$target\""
            val found = matcher.match(history.exercise(stronglifts), row.type)
            if (found == row) null else "$stronglifts: resolved to ${found?.name}, not $target"
        }

        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    @Test
    fun `the program's lifts are barbell lifts, in the table and in the catalogue`() {
        STRONGLIFTS_BARBELL_LIFTS.forEach { lift ->
            val target = STRONGLIFTS_CATALOGUE_NAMES[lift]
            assertEquals(lift, EquipmentType.BARBELL, byName.getValue(checkNotNull(target) { lift }).equipmentType)
            assertEquals(lift, EquipmentType.BARBELL, history.exercise(lift).equipment)
        }
        // The older layout's spelling is the same lift.
        assertEquals(EquipmentType.BARBELL, history.exercise("Overhead press").equipment)
        assertEquals("Standing Military Press", history.exercise("Overhead press").catalogueName)
    }

    @Test
    fun `the library names the catalogue already spells the same way need no entry`() {
        mapOf(
            "Pullups" to "Pullups",
            "Pushups" to "Pushups",
            "Barbell Curl" to "Barbell Curl",
            "Romanian Deadlift" to "Romanian Deadlift",
            "Barbell Shrug" to "Barbell Shrug",
            "Good Morning" to "Good Morning",
        ).forEach { (stronglifts, target) ->
            val row = byName.getValue(target)
            assertNull(stronglifts, STRONGLIFTS_CATALOGUE_NAMES[stronglifts])
            assertEquals(stronglifts, row, matcher.match(history.exercise(stronglifts), row.type))
        }
    }

    @Test
    fun `an entry that is also another source's name names the same row, and none is listed twice`() {
        val own = STRONGLIFTS_CATALOGUE_NAMES.mapKeys { (name, _) -> ExerciseMatcher.normalised(name) }

        listOf(SUFFIXED_CATALOGUE_NAMES, FITNOTES_CATALOGUE_NAMES, JEFIT_CATALOGUE_NAMES, FITBOD_CATALOGUE_NAMES)
            .forEach { table ->
                val other = table.mapKeys { (name, _) -> ExerciseMatcher.normalised(name) }
                val conflicts = own.filter { (name, target) -> other[name] != null && other[name] != target }
                assertTrue(conflicts.toString(), conflicts.isEmpty())
            }
        assertEquals(STRONGLIFTS_CATALOGUE_NAMES.size, own.size)
    }

    @Test
    fun `the near misses stay custom`() {
        // Chest or triceps dips, a held plank the catalogue counts in reps, and a knee raise that
        // is not the catalogue's leg raise.
        assertNull(matcher.match(history.exercise("Dips"), ExerciseType.STRENGTH))
        assertNull(matcher.match(history.exercise("Planks"), ExerciseType.ISOMETRIC))
        assertNull(matcher.match(history.exercise("Hanging Knee Raises"), ExerciseType.STRENGTH))
    }
}
