package com.titan.core.domain.importer

import com.titan.core.domain.model.EquipmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuffixedExerciseNamesTest {

    private val names = SuffixedExerciseNames(
        mapOf("Bench Press (Barbell)" to "Barbell Bench Press - Medium Grip", "Pull Up" to "Pullups"),
    )

    @Test
    fun `a trailing parenthesis splits off, and anything else is the whole name`() {
        assertEquals("Bench Press" to "Barbell", SuffixedExerciseNames.split("Bench Press (Barbell)"))
        assertEquals(
            "Bench Press - Close Grip" to "Barbell",
            SuffixedExerciseNames.split(" Bench Press - Close Grip(Barbell) "),
        )
        assertEquals(
            "Triceps Pushdown" to "Cable - Straight Bar",
            SuffixedExerciseNames.split("Triceps Pushdown (Cable - Straight Bar)"),
        )
        assertEquals("Pull Up" to null, SuffixedExerciseNames.split("Pull Up"))
        assertEquals(
            "Machine Shoulder (Military) Press" to null,
            SuffixedExerciseNames.split("Machine Shoulder (Military) Press"),
        )
        assertEquals("(Barbell)" to null, SuffixedExerciseNames.split("(Barbell)"))
    }

    @Test
    fun `equipment reads from the label, with an attachment after a dash ignored`() {
        assertEquals(EquipmentType.BARBELL, SuffixedExerciseNames.equipmentOf("Barbell"))
        assertEquals(EquipmentType.CABLE, SuffixedExerciseNames.equipmentOf("Cable - Straight Bar"))
        assertEquals(EquipmentType.MACHINE, SuffixedExerciseNames.equipmentOf("Smith Machine"))
        assertEquals(EquipmentType.MACHINE, SuffixedExerciseNames.equipmentOf("Smith"))
        assertEquals(EquipmentType.MACHINE, SuffixedExerciseNames.equipmentOf("plate loaded"))
        assertEquals(EquipmentType.EXERCISE_BALL, SuffixedExerciseNames.equipmentOf("Stability Ball"))
        assertEquals(EquipmentType.EZ_CURL_BAR, SuffixedExerciseNames.equipmentOf("EZ Bar"))
    }

    @Test
    fun `a label that is a variation rather than equipment names none`() {
        listOf("Assisted", "Knees", "Single Arm", "Indoor", "Plate", "").forEach { label ->
            assertNull(label, SuffixedExerciseNames.equipmentOf(label))
        }
    }

    @Test
    fun `the source's own table names the catalogue exercise first, whatever the case or spacing`() {
        val exercise = names.exercise("  bench press (BARBELL) ")

        assertEquals(
            ImportedExercise(
                name = "bench press (BARBELL)",
                catalogueName = "Barbell Bench Press - Medium Grip",
                equipment = EquipmentType.BARBELL,
            ),
            exercise,
        )
    }

    @Test
    fun `a name with known equipment is tried without its parenthesis`() {
        assertEquals(
            ImportedExercise(
                name = "Romanian Deadlift (Barbell)",
                catalogueName = "Romanian Deadlift",
                equipment = EquipmentType.BARBELL,
            ),
            names.exercise("Romanian Deadlift (Barbell)"),
        )
    }

    @Test
    fun `a variation keeps its whole name and claims no equipment`() {
        assertEquals(ImportedExercise(name = "Pull Up (Assisted)"), names.exercise("Pull Up (Assisted)"))
    }

    @Test
    fun `only an assisted variation is assisted`() {
        assertTrue(SuffixedExerciseNames.isAssisted("Pull Up (Assisted)"))
        assertTrue(SuffixedExerciseNames.isAssisted(" chin up (ASSISTED) "))
        assertFalse(SuffixedExerciseNames.isAssisted("Pull Up (Weighted)"))
        assertFalse(SuffixedExerciseNames.isAssisted("Assisted Pistol Squats"))
        assertFalse(SuffixedExerciseNames.isAssisted("Pull Up"))
    }

    @Test
    fun `equipment at the front of a name is read only when asked for, and only where a name follows`() {
        assertEquals(EquipmentType.BARBELL, SuffixedExerciseNames.leadingEquipmentOf("Barbell Bench Press"))
        assertEquals(EquipmentType.MACHINE, SuffixedExerciseNames.leadingEquipmentOf("Smith Machine Squat"))
        assertEquals(EquipmentType.EZ_CURL_BAR, SuffixedExerciseNames.leadingEquipmentOf("EZ-Bar Curl"))
        assertNull(SuffixedExerciseNames.leadingEquipmentOf("Barbell"))
        assertNull(SuffixedExerciseNames.leadingEquipmentOf("Bench Dip"))
        assertNull(SuffixedExerciseNames.leadingEquipmentOf("Barbells Galore"))
        // Not read at the front: the catalogue files such names under other equipment.
        assertNull(SuffixedExerciseNames.leadingEquipmentOf("Band Assisted Pull-Up"))
        assertNull(SuffixedExerciseNames.leadingEquipmentOf("Bodyweight Walking Lunge"))
        assertNull(SuffixedExerciseNames.leadingEquipmentOf("Smith Incline Shoulder Raise"))

        val leading = SuffixedExerciseNames(mapOf("Cable Seated Row" to "Seated Cable Rows"), leadingEquipment = true)
        assertEquals(
            ImportedExercise(
                name = "Cable Seated Row",
                catalogueName = "Seated Cable Rows",
                equipment = EquipmentType.CABLE,
            ),
            leading.exercise("Cable Seated Row"),
        )
        // The name is kept whole, and a parenthesis that names equipment wins.
        assertEquals(
            ImportedExercise(name = "Dumbbell Squat", equipment = EquipmentType.DUMBBELL),
            leading.exercise("Dumbbell Squat"),
        )
        assertEquals(EquipmentType.CABLE, leading.exercise("Machine Row (Cable)").equipment)
        assertEquals(ImportedExercise(name = "Dumbbell Squat"), names.exercise("Dumbbell Squat"))
    }

    @Test
    fun `a plain name is mapped by the table or left to the matcher`() {
        assertEquals(ImportedExercise(name = "Pull Up", catalogueName = "Pullups"), names.exercise("Pull Up"))
        assertEquals(ImportedExercise(name = "Bulgarian Split Squat"), names.exercise("Bulgarian Split Squat"))
    }
}
