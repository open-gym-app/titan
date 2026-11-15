package com.titan.core.domain.importer

import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SetType
import com.titan.core.domain.model.WorkoutSet
import com.titan.core.domain.validation.SetValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules every import source is held to, as a pure function from a parsed history and the
 * device's state to what will be written.
 */
class ImportPlannerTest {

    private val bench = Exercise(id = 1, name = "Bench Press", equipmentType = EquipmentType.BARBELL)
    private val run = Exercise(
        id = 2,
        name = "Running",
        type = ExerciseType.ENDURANCE,
        equipmentType = EquipmentType.OTHER,
    )
    private val chest = MuscleGroup(id = 7, name = "CHEST")

    private val baseline = ImportBaseline(
        exercises = listOf(bench, run),
        muscleGroups = listOf(chest),
        sessionStarts = emptySet(),
        measurementTimes = emptySet(),
        templates = emptyList(),
        nowMillis = NOW,
    )

    private val importedBench = ImportedExercise(name = "bench press")
    private val importedRun = ImportedExercise(name = "Running")

    private fun workout(
        startedAt: Long = DAY,
        durationSeconds: Long? = 3_600,
        vararg exercises: ImportedWorkoutExercise = arrayOf(
            line(importedBench, ImportedSet(reps = 5, weight = KG_100)),
        ),
    ) = ImportedWorkout(startedAt = startedAt, durationSeconds = durationSeconds, exercises = exercises.toList())

    private fun line(
        exercise: ImportedExercise,
        vararg sets: ImportedSet,
        supersetGroup: Int? = null,
    ) = ImportedWorkoutExercise(exercise = exercise, sets = sets.toList(), supersetGroup = supersetGroup)

    private fun plan(
        history: ImportedHistory,
        on: ImportBaseline = baseline,
    ) = ImportPlanner.plan(history, on)

    // ------------------------------------------------------------------ workouts

    @Test
    fun `a workout becomes a finished session against the exercise already on the device`() {
        val planned = plan(ImportedHistory(workouts = listOf(workout()))).workouts.single()

        assertEquals(DAY, planned.startedAt)
        assertEquals(DAY + 3_600_000, planned.finishedAt)
        assertEquals(ExerciseRef.Existing(bench.id), planned.exercises.single().exercise)
    }

    @Test
    fun `every planned set passes the validator, is completed and is numbered from 1`() {
        val sets = plan(
            ImportedHistory(
                workouts = listOf(
                    workout(
                        exercises = arrayOf(
                            line(
                                importedBench,
                                ImportedSet(reps = 5, weight = KG_100),
                                ImportedSet(reps = 3, weight = KG_100),
                            ),
                        ),
                    ),
                ),
            ),
        ).workouts.single().exercises.single().sets

        assertEquals(listOf(1, 2), sets.map(WorkoutSet::setNumber))
        assertTrue(sets.all { it.isCompleted && SetValidator.validate(it, ExerciseType.STRENGTH).isValid })
    }

    @Test
    fun `set timestamps spread across the workout in order, the last at the finish`() {
        val sets = plan(
            ImportedHistory(
                workouts = listOf(
                    workout(
                        durationSeconds = 600,
                        exercises = arrayOf(
                            line(importedBench, ImportedSet(reps = 5), ImportedSet(reps = 5)),
                            line(importedRun, ImportedSet(duration = 300, distance = 100_000)),
                        ),
                    ),
                ),
            ),
        ).workouts.single().exercises.flatMap { it.sets }

        assertEquals(listOf(DAY + 200_000, DAY + 400_000, DAY + 600_000), sets.map(WorkoutSet::completedAt))
        assertTrue(sets.all { it.createdAt == it.completedAt && it.updatedAt == it.completedAt })
    }

    @Test
    fun `a workout with no duration is estimated at two minutes a set`() {
        val planned = plan(ImportedHistory(workouts = listOf(workout(durationSeconds = null)))).workouts.single()

        assertEquals(DAY + ImportPlanner.ESTIMATED_SECONDS_PER_SET * 1_000, planned.finishedAt)
    }

    @Test
    fun `a duration past a day is a forgotten timer and is estimated instead`() {
        val planned = plan(ImportedHistory(workouts = listOf(workout(durationSeconds = 3 * 86_400)))).workouts.single()

        assertEquals(DAY + ImportPlanner.ESTIMATED_SECONDS_PER_SET * 1_000, planned.finishedAt)
    }

    @Test
    fun `a workout that would finish in the future finishes now`() {
        val planned = plan(ImportedHistory(workouts = listOf(workout(startedAt = NOW - 60_000)))).workouts.single()

        assertEquals(NOW, planned.finishedAt)
    }

    @Test
    fun `a workout before 1970 or after now is refused`() {
        val plan = plan(ImportedHistory(workouts = listOf(workout(startedAt = -1), workout(startedAt = NOW + 1))))

        assertTrue(plan.workouts.isEmpty())
        assertEquals(2, plan.skipped.invalidWorkouts)
    }

    @Test
    fun `workouts are planned in the order they finished, whatever order the file held them in`() {
        val plan = plan(
            ImportedHistory(
                workouts = listOf(
                    workout(startedAt = 3 * DAY),
                    workout(startedAt = DAY, durationSeconds = 600),
                    workout(startedAt = DAY - 60_000, durationSeconds = 7_200),
                ),
            ),
        )

        assertEquals(listOf(DAY, DAY - 60_000, 3 * DAY), plan.workouts.map(PlannedWorkout::startedAt))
    }

    @Test
    fun `a workout that started when a session on the device did is already there`() {
        val plan = plan(ImportedHistory(workouts = listOf(workout())), baseline.copy(sessionStarts = setOf(DAY)))

        assertTrue(plan.workouts.isEmpty())
        assertEquals(1, plan.skipped.duplicateWorkouts)
    }

    @Test
    fun `the same workout twice in one file is written once`() {
        val plan = plan(ImportedHistory(workouts = listOf(workout(), workout())))

        assertEquals(1, plan.workouts.size)
        assertEquals(1, plan.skipped.duplicateWorkouts)
    }

    @Test
    fun `a workout whose every set is refused is refused, and its sets are counted`() {
        val plan = plan(
            ImportedHistory(
                workouts = listOf(workout(exercises = arrayOf(line(importedBench, ImportedSet(weight = KG_100))))),
            ),
        )

        assertTrue(plan.workouts.isEmpty())
        assertEquals(1, plan.skipped.invalidWorkouts)
        assertEquals(1, plan.skipped.invalidSets)
    }

    @Test
    fun `a refused set leaves the rest numbered densely`() {
        val sets = plan(
            ImportedHistory(
                workouts = listOf(
                    workout(
                        exercises = arrayOf(
                            line(
                                importedBench,
                                ImportedSet(reps = 5),
                                ImportedSet(reps = 20_000),
                                ImportedSet(reps = 3),
                            ),
                        ),
                    ),
                ),
            ),
        ).let { plan ->
            assertEquals(1, plan.skipped.invalidSets)
            plan.workouts.single().exercises.single().sets
        }

        assertEquals(listOf(5 to 1, 3 to 2), sets.map { it.reps to it.setNumber })
    }

    @Test
    fun `values with an obvious reading are repaired rather than costing the set`() {
        val set = plan(
            ImportedHistory(
                workouts = listOf(
                    workout(
                        exercises = arrayOf(
                            line(
                                importedBench,
                                ImportedSet(
                                    reps = 8,
                                    weight = -20_000_000,
                                    rpe = 0,
                                    restAfter = -5,
                                    heartRateAvg = 0,
                                    heartRateMax = -1,
                                    distance = 0,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ).workouts.single().exercises.single().sets.single()

        assertEquals(8, set.reps)
        assertNull(set.weight)
        assertNull(set.rpe)
        assertNull(set.restAfter)
        assertNull(set.heartRateAvg)
        assertNull(set.heartRateMax)
        assertNull(set.distance)
    }

    @Test
    fun `a zero weight is a bodyweight set on a lift and no weight at all on a run`() {
        val exercises = plan(
            ImportedHistory(
                workouts = listOf(
                    workout(
                        exercises = arrayOf(
                            line(importedBench, ImportedSet(reps = 10, weight = 0)),
                            line(importedRun, ImportedSet(duration = 600, distance = 200_000, weight = 0)),
                        ),
                    ),
                ),
            ),
        ).workouts.single().exercises

        assertEquals(0L, exercises[0].sets.single().weight)
        assertNull(exercises[1].sets.single().weight)
    }

    @Test
    fun `a superset that breaks the rules is torn apart rather than refused`() {
        val exercises = plan(
            ImportedHistory(
                workouts = listOf(
                    workout(
                        exercises = arrayOf(
                            line(importedBench, ImportedSet(reps = 5), supersetGroup = 9),
                            line(importedRun, ImportedSet(duration = 60), supersetGroup = 4),
                        ),
                    ),
                ),
            ),
        ).workouts.single().exercises

        assertEquals(listOf<Int?>(null, null), exercises.map(PlannedWorkoutExercise::supersetGroup))
    }

    @Test
    fun `a well-formed superset is kept and renumbered from zero`() {
        val exercises = plan(
            ImportedHistory(
                workouts = listOf(
                    workout(
                        exercises = arrayOf(
                            line(importedBench, ImportedSet(reps = 5), supersetGroup = 9),
                            line(importedRun, ImportedSet(duration = 60), supersetGroup = 9),
                        ),
                    ),
                ),
            ),
        ).workouts.single().exercises

        assertEquals(listOf<Int?>(0, 0), exercises.map(PlannedWorkoutExercise::supersetGroup))
    }

    @Test
    fun `names and notes are stored the way the app stores its own`() {
        val planned = plan(
            ImportedHistory(workouts = listOf(workout().copy(name = "   ", notes = "  felt strong \n"))),
        ).workouts.single()

        assertNull(planned.name)
        assertEquals("felt strong", planned.notes)
    }

    // ------------------------------------------------------------------ exercises

    @Test
    fun `an exercise nothing matches becomes one custom exercise, however many workouts name it`() {
        val curl = ImportedExercise(name = "  Cable\tCurl ", primaryMuscle = "chest")
        val plan = plan(
            ImportedHistory(
                workouts = listOf(
                    workout(startedAt = DAY, exercises = arrayOf(line(curl, ImportedSet(reps = 12)))),
                    workout(startedAt = 2 * DAY, exercises = arrayOf(line(curl, ImportedSet(reps = 10)))),
                ),
            ),
        )

        val created = plan.newExercises.single()
        assertEquals("Cable Curl", created.exercise.name)
        assertTrue(created.exercise.isCustom)
        assertEquals(EquipmentType.OTHER, created.exercise.equipmentType)
        assertEquals(chest, created.primaryMuscle)
        assertEquals(
            setOf(ExerciseRef.New(0)),
            plan.workouts.flatMap { it.exercises.map(PlannedWorkoutExercise::exercise) }.toSet(),
        )
        assertEquals(0, plan.matchedExercises)
    }

    @Test
    fun `an exercise whose every set was refused is still created, so nothing the user had goes missing`() {
        val ghost = ImportedExercise(name = "Ghost Lift")
        val plan = plan(
            ImportedHistory(
                workouts = listOf(
                    workout(
                        exercises = arrayOf(
                            line(ghost, ImportedSet(reps = 0)),
                            line(importedBench, ImportedSet(reps = 5)),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("Ghost Lift"), plan.newExercises.map { it.exercise.name })
        assertEquals(1, plan.skipped.invalidSets)
        assertEquals(1, plan.matchedExercises)
    }

    @Test
    fun `an exercise only the source's library lists is created, with the details only it carries`() {
        val sled = ImportedExercise(
            name = "Sled Push",
            type = ExerciseType.ENDURANCE,
            equipment = EquipmentType.OTHER,
            notes = "  lane 3  ",
            defaultRestSeconds = 180,
        )
        val created = plan(ImportedHistory(exercises = listOf(sled))).newExercises.single().exercise

        assertEquals("Sled Push", created.name)
        assertEquals(ExerciseType.ENDURANCE, created.type)
        assertEquals("lane 3", created.notes)
        assertEquals(180L, created.defaultRest)
    }

    @Test
    fun `a library entry for an exercise a workout already named resolves to the same one`() {
        val curl = ImportedExercise(name = "Cable Curl")
        val plan = plan(
            ImportedHistory(
                exercises = listOf(curl, importedBench),
                workouts = listOf(workout(exercises = arrayOf(line(curl, ImportedSet(reps = 12))))),
            ),
        )

        assertEquals(1, plan.newExercises.size)
        assertEquals(1, plan.matchedExercises)
    }

    @Test
    fun `a rest the timer would ignore is not stored`() {
        val created = plan(
            ImportedHistory(exercises = listOf(ImportedExercise(name = "Curl", defaultRestSeconds = 0))),
        ).newExercises.single().exercise

        assertNull(created.defaultRest)
    }

    @Test
    fun `an exercise logged by time and distance is matched as endurance, not as the lift of the same name`() {
        val walk = ImportedExercise(name = "Bench Press")
        val plan = plan(
            ImportedHistory(
                workouts = listOf(
                    workout(exercises = arrayOf(line(walk, ImportedSet(duration = 60, distance = 5_000)))),
                ),
            ),
        )

        assertEquals(ExerciseType.ENDURANCE, plan.newExercises.single().exercise.type)
        assertEquals(1, plan.workouts.single().setCount)
    }

    @Test
    fun `an exercise's type is read off the shapes of its sets`() {
        fun shape(
            reps: Int? = null,
            duration: Long? = null,
            distance: Long? = null,
        ) = ImportPlanner.SetShape(reps, duration, distance)

        assertEquals(ExerciseType.STRENGTH, ImportPlanner.inferType(emptyList()))
        assertEquals(ExerciseType.STRENGTH, ImportPlanner.inferType(listOf(shape(reps = 5), shape(duration = 30))))
        assertEquals(
            ExerciseType.ISOMETRIC,
            ImportPlanner.inferType(listOf(shape(duration = 30), shape(duration = 45))),
        )
        assertEquals(ExerciseType.ENDURANCE, ImportPlanner.inferType(listOf(shape(duration = 30, distance = 100))))
    }

    @Test
    fun `a type the parser states is used as stated`() {
        val hold = ImportedExercise(name = "Wall Sit", type = ExerciseType.ISOMETRIC)
        val plan = plan(
            ImportedHistory(
                workouts = listOf(workout(exercises = arrayOf(line(hold, ImportedSet(reps = 1, duration = 90))))),
            ),
        )

        assertEquals(ExerciseType.ISOMETRIC, plan.newExercises.single().exercise.type)
    }

    // ------------------------------------------------------------------ templates

    private val template = ImportedTemplate(
        name = "Push Day",
        exercises = listOf(
            ImportedTemplateExercise(importedBench, sets = listOf(PlannedSet(reps = 5, weight = KG_100))),
        ),
    )

    @Test
    fun `a template is planned against the device's exercises`() {
        val planned = plan(ImportedHistory(templates = listOf(template))).templates.single()

        assertEquals("Push Day", planned.name)
        assertEquals(ExerciseRef.Existing(bench.id), planned.exercises.single().exercise)
        assertEquals(listOf(PlannedSet(reps = 5, weight = KG_100)), planned.exercises.single().sets)
    }

    @Test
    fun `a template the device already has, by name and exercises, is a duplicate`() {
        val plan = plan(
            ImportedHistory(templates = listOf(template)),
            baseline.copy(templates = listOf(ExistingTemplate(name = "push day", exerciseIds = listOf(bench.id)))),
        )

        assertTrue(plan.templates.isEmpty())
        assertEquals(1, plan.skipped.duplicateTemplates)
    }

    @Test
    fun `a template that only shares a name is imported under a numbered one`() {
        val plan = plan(
            ImportedHistory(templates = listOf(template)),
            baseline.copy(
                templates = listOf(
                    ExistingTemplate(name = "Push Day", exerciseIds = listOf(run.id)),
                    ExistingTemplate(name = "Push Day (2)", exerciseIds = listOf(run.id)),
                ),
            ),
        )

        assertEquals("Push Day (3)", plan.templates.single().name)
    }

    @Test
    fun `a template with no name or no lines is refused`() {
        val plan = plan(
            ImportedHistory(templates = listOf(template.copy(name = " "), template.copy(exercises = emptyList()))),
        )

        assertTrue(plan.templates.isEmpty())
        assertEquals(2, plan.skipped.invalidTemplates)
    }

    @Test
    fun `planned figures outside the set bounds are left unplanned`() {
        val planned = plan(
            ImportedHistory(
                templates = listOf(
                    template.copy(
                        exercises = listOf(
                            ImportedTemplateExercise(
                                importedBench,
                                sets = listOf(
                                    PlannedSet(
                                        setType = SetType.WARMUP,
                                        reps = 0,
                                        weight = -1,
                                        duration = 0,
                                        distance = -1,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ).templates.single().exercises.single().sets.single()

        assertEquals(PlannedSet(setType = SetType.WARMUP), planned)
    }

    // ------------------------------------------------------------------ measurements

    private fun measurement(
        at: Long = DAY,
        weight: Long? = 80_000_000,
    ) = BodyMeasurement(id = 99, weight = weight, createdAt = at, updatedAt = 1)

    @Test
    fun `a measurement is stored as taken, with the store left to assign its id`() {
        val planned = plan(ImportedHistory(bodyMeasurements = listOf(measurement()))).measurements.single()

        assertEquals(0L, planned.id)
        assertEquals(DAY, planned.updatedAt)
    }

    @Test
    fun `every site the body screen records comes through, the extra five included`() {
        val sites = BodyMeasurement(
            neck = 390,
            shoulders = 1_210,
            chest = 1_040,
            waist = 820,
            hips = 980,
            arm = 385,
            forearm = 300,
            thigh = 610,
            calf = 390,
            createdAt = DAY,
            updatedAt = DAY,
        )

        val planned = plan(ImportedHistory(bodyMeasurements = listOf(sites))).measurements.single()

        assertEquals(sites.circumferences, planned.circumferences)
    }

    @Test
    fun `a measurement of one of the extra sites alone is a measurement`() {
        val neckOnly = BodyMeasurement(neck = 390, createdAt = DAY, updatedAt = DAY)

        assertEquals(1, plan(ImportedHistory(bodyMeasurements = listOf(neckOnly))).measurements.size)
    }

    @Test
    fun `a measurement recording nothing, or dated in the future, is refused`() {
        val plan = plan(
            ImportedHistory(bodyMeasurements = listOf(measurement(weight = null), measurement(at = NOW + 1))),
        )

        assertTrue(plan.measurements.isEmpty())
        assertEquals(2, plan.skipped.invalidMeasurements)
    }

    @Test
    fun `a measurement taken when one on the device was is a duplicate`() {
        val plan = plan(
            ImportedHistory(bodyMeasurements = listOf(measurement(), measurement(at = 2 * DAY))),
            baseline.copy(measurementTimes = setOf(DAY)),
        )

        assertEquals(listOf(2 * DAY), plan.measurements.map(BodyMeasurement::createdAt))
        assertEquals(1, plan.skipped.duplicateMeasurements)
    }

    @Test
    fun `a history with nothing wrong in it skips nothing`() {
        val plan = plan(
            ImportedHistory(
                workouts = listOf(workout()),
                templates = listOf(template),
                bodyMeasurements = listOf(measurement()),
            ),
        )

        assertTrue(plan.skipped.isEmpty)
    }

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 30 * DAY
        const val KG_100 = 100_000_000L
    }
}
