package com.titan.core.domain.importer

import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseMatcherTest {

    private val benchPress = Exercise(
        id = 10,
        name = "Barbell Bench Press - Medium Grip",
        equipmentType = EquipmentType.BARBELL,
    )
    private val plank = Exercise(
        id = 20,
        name = "Plank",
        type = ExerciseType.ISOMETRIC,
        equipmentType = EquipmentType.BODYWEIGHT,
    )
    private val squat = Exercise(id = 30, name = "Squat", equipmentType = EquipmentType.BARBELL)
    private val customSquat = Exercise(id = 40, name = "squat", equipmentType = EquipmentType.BARBELL, isCustom = true)

    private val matcher = ExerciseMatcher(listOf(customSquat, plank, benchPress, squat))

    @Test
    fun `names match once accents, case and punctuation are gone`() {
        val found = matcher.match(ImportedExercise(name = "barbell bench press (medium grip)"), ExerciseType.STRENGTH)

        assertEquals(benchPress, found)
    }

    @Test
    fun `the parser's catalogue name is tried before the source's own name`() {
        val imported = ImportedExercise(
            name = "Bench Press (Barbell)",
            catalogueName = "Barbell Bench Press - Medium Grip",
        )

        assertEquals(benchPress, matcher.match(imported, ExerciseType.STRENGTH))
    }

    @Test
    fun `a catalogue name that matches nothing falls back to the source name`() {
        val imported = ImportedExercise(name = "Plank", catalogueName = "Front Plank Hold")

        assertEquals(plank, matcher.match(imported, ExerciseType.ISOMETRIC))
    }

    @Test
    fun `a type the sets were not logged as is no match, so the history keeps its sets`() {
        assertNull(matcher.match(ImportedExercise(name = "Plank"), ExerciseType.STRENGTH))
    }

    @Test
    fun `equipment the source names has to agree`() {
        assertNull(
            matcher.match(ImportedExercise(name = "Squat", equipment = EquipmentType.DUMBBELL), ExerciseType.STRENGTH),
        )
    }

    @Test
    fun `a source that names no equipment matches any`() {
        assertEquals(plank, matcher.match(ImportedExercise(name = "Plank"), ExerciseType.ISOMETRIC))
    }

    @Test
    fun `several candidates resolve to the lowest id, the same one every time`() {
        assertEquals(squat, matcher.match(ImportedExercise(name = "SQUAT"), ExerciseType.STRENGTH))
    }

    @Test
    fun `nothing fuzzy - a different word order is a different name`() {
        assertNull(matcher.match(ImportedExercise(name = "Press Bench Barbell Medium Grip"), ExerciseType.STRENGTH))
    }

    @Test
    fun `normalising strips accents and folds separators to single spaces`() {
        assertEquals("developpe couche", ExerciseMatcher.normalised("  Développé—Couché! "))
    }
}
