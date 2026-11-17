package com.titan.core.domain.importer.fitnotes

import com.titan.core.domain.importer.ExerciseRef
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportPlanner
import com.titan.core.domain.importer.ImportedExercise
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImporterFixtures
import com.titan.core.domain.importer.ImporterFixtures.IMPERIAL_UTC
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * The FitNotes parser over sample exports in each of the layouts FitNotes has written — the
 * current one, the older one with the unit in the weight's header, FitNotes for iOS's — and its
 * body tracker export, and over the files it has to turn away or survive.
 */
class FitNotesParserTest {

    private val parser = FitNotesParser()

    private fun parsed(
        sample: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.resource("fitnotes/$sample"), context)

    private fun parsedText(
        csv: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.text(csv), context)

    private fun read(csv: String): ParseResult = ImporterFixtures.read(parser, ImporterFixtures.text(csv))

    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()

    private fun ImportedWorkout.line(index: Int) = exercises[index]

    private fun ImportedWorkout.names() = exercises.map { it.exercise.name }

    private val plannedNow = millis("2026-11-16T00:00:00Z")

    private val header = "Date,Exercise,Category,Weight,Weight Unit,Reps,Distance,Distance Unit,Time,Comment\n"

    // ------------------------------------------------------------------ recognising the file

    @Test
    fun `anything that is not a FitNotes export is not recognised`() {
        listOf(
            ImporterFixtures.bytes(ByteArray(0)),
            ImporterFixtures.text("hello world\nthis is a shopping list\n"),
            ImporterFixtures.bytes(Random(seed = 249).nextBytes(4_096)),
            // Almost a workout export, without a weight column.
            ImporterFixtures.text(
                "Date,Exercise,Category,Reps,Distance,Distance Unit,Time\n2026-10-05,Bench,Chest,5,,,\n",
            ),
            // Almost a workout export, without the category.
            ImporterFixtures.text("Date,Exercise,Weight,Weight Unit,Reps\n2026-10-05,Bench,80,kgs,5\n"),
            // Almost a body tracker export, without the unit.
            ImporterFixtures.text("Date,Time,Measurement,Value\n2026-10-01,07:10:00,Bodyweight,82\n"),
            ImporterFixtures.text("\"Date,Exercise,Category,Weight,Reps\n"),
        ).forEachIndexed { index, source ->
            assertEquals("source $index", ParseResult.NotRecognised, ImporterFixtures.read(parser, source))
        }
    }

    @Test
    fun `the full backup is a database, not a spreadsheet, and is not recognised rather than broken`() {
        assertEquals(
            ParseResult.NotRecognised,
            ImporterFixtures.read(parser, ImporterFixtures.resource("fitnotes/FitNotes_Backup.fitnotes")),
        )
    }

    @Test
    fun `no Strong or Hevy export is a FitNotes export`() {
        listOf(
            "strong/strong-ios.csv",
            "strong/strong-android.csv",
            "strong/strong-unit-columns.csv",
            "strong/strong-early.csv",
            "strong/strong-damaged.csv",
            "strong/strong-header-only.csv",
            "hevy/hevy-workouts.csv",
            "hevy/hevy-workouts-web.csv",
            "hevy/hevy-workouts-de.csv",
            "hevy/hevy-measurements.csv",
            "hevy/hevy-damaged.csv",
        ).forEach { sample ->
            assertEquals(
                sample,
                ParseResult.NotRecognised,
                ImporterFixtures.read(parser, ImporterFixtures.resource(sample)),
            )
        }
    }

    @Test
    fun `an export with no workouts or no measurements in it is an empty history, not a broken file`() {
        assertEquals(ImportedHistory(), parsedText(header))
        assertEquals(ImportedHistory(), parsedText("Date,Time,Measurement,Value,Unit,Comment"))
    }

    // ------------------------------------------------------------------ the workout export

    @Test
    fun `an export becomes one workout a day, starting at noon, its exercises in the order first logged`() {
        val workouts = parsed("fitnotes-export.csv").workouts

        assertEquals(
            listOf(millis("2026-10-05T12:00:00Z"), millis("2026-10-07T12:00:00Z"), millis("2026-10-09T12:00:00Z")),
            workouts.map(ImportedWorkout::startedAt),
        )
        // FitNotes records no time of day, no duration, no workout name and no workout note.
        workouts.forEach { workout ->
            assertNull(workout.durationSeconds)
            assertNull(workout.name)
            assertNull(workout.notes)
        }
        assertEquals(
            listOf(
                "Flat Barbell Bench Press",
                "Lateral Dumbbell Raise",
                "Cable Face Pull",
                "Pull Up",
                "Rope Push Down",
                "Plank",
            ),
            workouts[0].names(),
        )
    }

    @Test
    fun `every set keeps its weight, reps, time, distance and comment, in Titan's units`() {
        val workouts = parsed("fitnotes-export.csv").workouts

        assertEquals(
            listOf(
                ImportedSet(reps = 8, weight = 60_000_000, notes = "Warm-up"),
                ImportedSet(reps = 5, weight = 80_000_000),
                ImportedSet(reps = 5, weight = 80_000_000, notes = "Felt strong, RPE 8"),
            ),
            workouts[0].line(0).sets,
        )
        // A bodyweight set is a zero weight, as FitNotes writes it.
        assertEquals(listOf(ImportedSet(reps = 10, weight = 0)), workouts[0].line(3).sets)
        assertEquals(
            listOf(ImportedSet(reps = 12, weight = 30_000_000, notes = "Elbows \"pinned\"")),
            workouts[0].line(4).sets,
        )
        assertEquals(listOf(ImportedSet(weight = 0, duration = 60)), workouts[0].line(5).sets)

        val legs = workouts[1]
        assertEquals(
            listOf(ImportedSet(duration = 1_530, distance = 500_000, notes = "Zone 2")),
            legs.line(4).sets,
        )
        // Timed and not measured: FitNotes writes a distance of 0.0, and the planner reads it as none.
        assertEquals(listOf(ImportedSet(duration = 600, distance = 0)), legs.line(5).sets)

        val pull = workouts[2]
        assertEquals(listOf(ImportedSet(duration = 480, distance = 200_000)), pull.line(2).sets)
        // 500 ft is 152.4 m.
        assertEquals(listOf(ImportedSet(duration = 300, distance = 15_240)), pull.line(3).sets)
    }

    @Test
    fun `the sets of an exercise are one exercise however they were interleaved that day`() {
        val push = parsed("fitnotes-export.csv").workouts[0]

        assertEquals(listOf(ImportedSet(reps = 12, weight = 10_000_000)).let { it + it }, push.line(1).sets)
        assertEquals(listOf(ImportedSet(reps = 15, weight = 25_000_000)).let { it + it }, push.line(2).sets)
        // FitNotes does not export supersets.
        assertTrue(push.exercises.all { it.supersetGroup == null })
    }

    @Test
    fun `names map onto the catalogue, and a category that is a muscle group is the primary muscle`() {
        val workouts = parsed("fitnotes-export.csv").workouts

        assertEquals(
            ImportedExercise(
                name = "Flat Barbell Bench Press",
                catalogueName = "Barbell Bench Press - Medium Grip",
                primaryMuscle = "CHEST",
            ),
            workouts[0].line(0).exercise,
        )
        assertEquals("SHOULDERS", workouts[0].line(1).exercise.primaryMuscle)
        assertEquals("TRICEPS", workouts[0].line(4).exercise.primaryMuscle)
        assertEquals("ABDOMINALS", workouts[0].line(5).exercise.primaryMuscle)
        // A category the user added.
        assertEquals("CALVES", workouts[1].line(3).exercise.primaryMuscle)
        // "Back", "Legs" and "Cardio" are no one muscle.
        assertEquals(ImportedExercise(name = "Pull Up", catalogueName = "Pullups"), workouts[0].line(3).exercise)
        assertNull(workouts[1].line(0).exercise.primaryMuscle)
        assertNull(workouts[1].line(4).exercise.primaryMuscle)
    }

    @Test
    fun `cardio is endurance, even timed and not measured, and everything else is left to the planner`() {
        val workouts = parsed("fitnotes-export.csv").workouts

        assertEquals(
            ImportedExercise(
                name = "Running (Treadmill)",
                catalogueName = "Running",
                equipment = EquipmentType.MACHINE,
                type = ExerciseType.ENDURANCE,
            ),
            workouts[1].line(4).exercise,
        )
        assertEquals(ExerciseType.ENDURANCE, workouts[1].line(5).exercise.type)
        assertEquals(ExerciseType.ENDURANCE, workouts[2].line(3).exercise.type)
        assertNull(workouts[0].line(0).exercise.type)
        // The plank records a weight and a time; the planner reads it as a hold.
        assertNull(workouts[0].line(5).exercise.type)
    }

    @Test
    fun `a name the exporter split at its comma is put back together`() {
        val line = parsed("fitnotes-export.csv").workouts[2].line(1)

        assertEquals(ImportedExercise(name = "Curl, EZ Bar", primaryMuscle = "BICEPS"), line.exercise)
        assertEquals(listOf(ImportedSet(reps = 10, weight = 30_000_000)), line.sets)
    }

    @Test
    fun `a split name on a row with a bad figure is still put back together, and its set counted`() {
        val csv = header +
            "2026-10-05,Curl, EZ Bar,Biceps,heavy,kgs,10,,,,\"\"\n" +
            "2026-10-05,Run, Easy,Cardio,,,,5.0,leagues,0:30:00,\"\"\n" +
            "2026-10-05,Row, Easy,Cardio,,,,5.0,km,a while,\"\"\n"

        val workout = parsedText(csv).workouts.single()
        assertEquals(listOf("Curl, EZ Bar", "Run, Easy", "Row, Easy"), workout.names())
        assertTrue(workout.exercises.all { it.sets == listOf(ImportedSet()) })
    }

    @Test
    fun `the rows of one day are one workout wherever they are, whichever separator the date has`() {
        val csv = header +
            "2026-10-05,Deadlift,Back,140.0,kgs,3,,,,\"\"\n" +
            "2026-10-06,Barbell Squat,Legs,100.0,kgs,5,,,,\"\"\n" +
            "2026/10/05,Deadlift,Back,150.0,kgs,1,,,,\"\"\n"

        val workouts = parsedText(csv).workouts
        assertEquals(2, workouts.size)
        assertEquals(
            listOf(ImportedSet(reps = 3, weight = 140_000_000), ImportedSet(reps = 1, weight = 150_000_000)),
            workouts[0].line(0).sets,
        )
    }

    @Test
    fun `a day starts at noon in the zone the user is in`() {
        val csv = header + "2026-10-05,Deadlift,Back,140.0,kgs,3,,,,\"\"\n"

        assertEquals(
            millis("2026-10-05T10:00:00Z"),
            parsedText(csv, ImporterFixtures.context("Europe/Paris")).workouts.single().startedAt,
        )
        assertEquals(
            millis("2026-10-05T16:00:00Z"),
            parsedText(csv, ImporterFixtures.context("America/New_York")).workouts.single().startedAt,
        )
    }

    @Test
    fun `a unit nothing in the file names is the user's own, and one on the row wins over it`() {
        val csv = header +
            "2026-10-05,Deadlift,Back,135.0,,3,,,,\"\"\n" +
            "2026-10-05,Deadlift,Back,100.0,kgs,3,,,,\"\"\n" +
            "2026-10-05,Running (Outdoor),Cardio,,,,3.1,,0:27:15,\"\"\n"

        val workout = parsedText(csv, IMPERIAL_UTC).workouts.single()
        // 135 lb is 61,234,969.95 mg; 3.1 mi is 498,896.64 cm.
        assertEquals(listOf(61_234_970L, 100_000_000L), workout.line(0).sets.map(ImportedSet::weight))
        assertEquals(498_897L, workout.line(1).sets.single().distance)
    }

    @Test
    fun `an older export with the unit in the weight's header reads its pounds and miles`() {
        val workouts = parsed("fitnotes-export-legacy.csv").workouts

        assertEquals(
            listOf(millis("2015-06-07T12:00:00Z"), millis("2015-06-09T12:00:00Z")),
            workouts.map { it.startedAt },
        )
        // 135 lb, 185 lb and 50 lb.
        assertEquals(
            listOf(ImportedSet(reps = 8, weight = 61_234_970), ImportedSet(reps = 5, weight = 83_914_588)),
            workouts[0].line(0).sets,
        )
        assertEquals(listOf(ImportedSet(reps = 10, weight = 22_679_619)), workouts[0].line(1).sets)
        assertEquals("Preacher Curl", workouts[0].line(1).exercise.catalogueName)
        assertEquals(
            listOf(ImportedSet(duration = 1_635, distance = 498_897, notes = "Hills")),
            workouts[1].line(0).sets,
        )
        assertEquals(ExerciseType.ENDURANCE, workouts[1].line(0).exercise.type)
    }

    @Test
    fun `an export without the comment column reads the same`() {
        val csv = "Date,Exercise,Category,Weight (kgs),Reps,Distance,Distance Unit,Time\n" +
            "2015-02-13,Deadlift,Back,145.0,3,,,\n"

        assertEquals(
            listOf(ImportedSet(reps = 3, weight = 145_000_000)),
            parsedText(csv).workouts.single().line(0).sets,
        )
    }

    @Test
    fun `an iOS export reads whichever weight column the row filled, its notes, short times and kinds`() {
        val workouts = parsed("fitnotes-ios.csv").workouts

        assertEquals(
            listOf(
                ImportedSet(reps = 5, weight = 80_000_000, notes = "Paused"),
                ImportedSet(reps = 3, weight = 83_914_588),
            ),
            workouts[0].line(0).sets,
        )
        // 1.5 mi in 25:00, the minutes and seconds iOS writes when there is no hour.
        val walk = workouts[0].line(1)
        assertEquals(listOf(ImportedSet(duration = 1_500, distance = 241_402)), walk.sets)
        assertEquals(ExerciseType.ENDURANCE, walk.exercise.type)
        // Jump rope is timed cardio; a dead hang is timed and not cardio, a hold for the planner.
        assertEquals(ExerciseType.ENDURANCE, workouts[1].line(0).exercise.type)
        assertEquals(listOf(ImportedSet(duration = 600)), workouts[1].line(0).sets)
        assertNull(workouts[1].line(1).exercise.type)
        assertEquals(listOf(ImportedSet(duration = 90)), workouts[1].line(1).sets)
    }

    @Test
    fun `a distance the iOS kind says is tracked makes the exercise endurance, even with no figure`() {
        val csv = "Date,Exercise,Category,Weight (kg),Weight (lbs),Reps,Distance,Distance Unit,Time,Notes,Kind\n" +
            "2026-09-01,Sled Push,Legs,,,,,,05:00,,DT\n"

        assertEquals(ExerciseType.ENDURANCE, parsedText(csv).workouts.single().line(0).exercise.type)
    }

    @Test
    fun `an iOS note split at its unquoted comma is put back together, and so is a name`() {
        val csv = "Date,Exercise,Category,Weight (kg),Weight (lbs),Reps,Distance,Distance Unit,Time,Notes,Kind\n" +
            "2026-09-01,Bench Press,Chest,80,,5,,,,Paused, slow,wr\n" +
            "2026-09-01,Bench Press,Chest,80,,5,,,,5, then a drop,wr\n" +
            "2026-09-01,Treadmill Walk,Cardio,,,,1.5,mi,25:00,Easy, flat,dt\n" +
            "2026-09-01,Curl, EZ Bar,Biceps,30,,10,,,,,wr\n"

        val workout = parsedText(csv).workouts.single()
        assertEquals(listOf("Bench Press", "Treadmill Walk", "Curl, EZ Bar"), workout.names())
        assertEquals(
            listOf(
                ImportedSet(reps = 5, weight = 80_000_000, notes = "Paused, slow"),
                ImportedSet(reps = 5, weight = 80_000_000, notes = "5, then a drop"),
            ),
            workout.line(0).sets,
        )
        val walk = ImportedSet(duration = 1_500, distance = 241_402, notes = "Easy, flat")
        assertEquals(listOf(walk), workout.line(1).sets)
        assertEquals(listOf(ImportedSet(reps = 10, weight = 30_000_000)), workout.line(2).sets)
    }

    @Test
    fun `a zero in Reps counts no reps, so a timed cardio row is still endurance`() {
        val csv = header + "2026-10-07,Jump Rope,Cardio,0.0,kgs,0,,,0:10:00,\"\"\n"

        assertEquals(ExerciseType.ENDURANCE, parsedText(csv).workouts.single().line(0).exercise.type)
    }

    @Test
    fun `an assisted exercise keeps its reps and drops the assistance`() {
        val csv = header + "2026-10-05,Pull Up (Assisted),Back,25.0,kgs,8,,,,\"\"\n"

        assertEquals(listOf(ImportedSet(reps = 8)), parsedText(csv).workouts.single().line(0).sets)
    }

    @Test
    fun `an assisted exercise's assistance is never read, so one that does not read costs nothing`() {
        val csv = header + "2026-10-05,Pull Up (Assisted),Back,a band,stone,8,,,,\"\"\n"

        assertEquals(listOf(ImportedSet(reps = 8)), parsedText(csv).workouts.single().line(0).sets)
    }

    // ------------------------------------------------------------------ the body tracker export

    @Test
    fun `a body tracker export becomes one measurement a moment, in Titan's units, both sides read as the larger`() {
        val measurements = parsed("fitnotes-body-tracker.csv").bodyMeasurements

        assertEquals(
            listOf(
                BodyMeasurement(
                    weight = 82_400_000,
                    bodyFat = 185,
                    waist = 840,
                    arm = 360,
                    notes = "Morning, fasted",
                    createdAt = millis("2026-10-01T07:10:00Z"),
                    updatedAt = millis("2026-10-01T07:10:00Z"),
                ),
                BodyMeasurement(
                    neck = 380,
                    createdAt = millis("2026-10-01T07:12:30Z"),
                    updatedAt = millis("2026-10-01T07:12:30Z"),
                ),
                // 181 lb, and calves of 15 in and 14.75 in. "Biceps Flexed" is the user's own.
                BodyMeasurement(
                    weight = 82_100_219,
                    calf = 381,
                    createdAt = millis("2026-10-08T07:05:00Z"),
                    updatedAt = millis("2026-10-08T07:05:00Z"),
                ),
                BodyMeasurement(
                    weight = 82_000_000,
                    createdAt = millis("2026-10-15T07:00:00Z"),
                    updatedAt = millis("2026-10-15T07:00:00Z"),
                ),
            ),
            measurements,
        )
    }

    @Test
    fun `every one of FitNotes' own measurements lands on its site, and a time is read in the user's zone`() {
        val csv = """
            Date,Time,Measurement,Value,Unit
            2026-10-01,07:10,Body Weight,80,
            2026-10-01,07:10,Body Fat %,15.5,
            2026-10-01,07:10,Neck,38,
            2026-10-01,07:10,Shoulders,120,
            2026-10-01,07:10,Chest,100,
            2026-10-01,07:10,Hips,95,
            2026-10-01,07:10,Upper Arm (Right),36,
            2026-10-01,07:10,Forearm (Left),30,
            2026-10-01,07:10,Forearm (Right),31,
            2026-10-01,07:10,Thigh (Left),58,
            2026-10-01,07:10,Thigh (Right),57,
            2026-10-01,07:10,Calf (Left),38,
        """.trimIndent()

        val entry = parsedText(csv, ImporterFixtures.context("Europe/Paris")).bodyMeasurements.single()
        assertEquals(
            BodyMeasurement(
                weight = 80_000_000,
                bodyFat = 155,
                neck = 380,
                shoulders = 1_200,
                chest = 1_000,
                hips = 950,
                arm = 360,
                forearm = 310,
                thigh = 580,
                calf = 380,
                createdAt = millis("2026-10-01T05:10:00Z"),
                updatedAt = millis("2026-10-01T05:10:00Z"),
            ),
            entry,
        )
    }

    @Test
    fun `a measurement with no unit is in the user's units`() {
        val csv = "Date,Time,Measurement,Value,Unit\n2026-10-01,07:10:00,Bodyweight,180,\n2026-10-01,07:10:00,Waist,33,\n"

        val entry = parsedText(csv, IMPERIAL_UTC).bodyMeasurements.single()
        assertEquals(81_646_627L, entry.weight)
        assertEquals(838, entry.waist)
    }

    @Test
    fun `a figure or a unit that does not read is passed on empty, for the planner to count, and the rest kept`() {
        val csv = """
            Date,Time,Measurement,Value,Unit,Comment
            2026-10-01,07:10:00,Bodyweight,82.4,kgs,""
            2026-10-01,07:10:00,Waist,slim,cm,""
            2026-10-01,07:10:00,Body Fat,18,kgs,""
            2026-10-01,07:10:00,Neck,15,stone,""
            last week,07:10:00,Bodyweight,82.4,kgs,""
            2026-10-02,,Bodyweight,82.4,kgs,""
        """.trimIndent()

        val history = parsedText(csv)
        assertEquals(4, history.bodyMeasurements.size)
        assertEquals(82_400_000L, history.bodyMeasurements[0].weight)
        assertTrue(history.bodyMeasurements.drop(1).all(BodyMeasurement::isEmpty))

        val plan = ImportPlanner.plan(history, ImporterFixtures.freshInstall(plannedNow))
        assertEquals(1, plan.measurements.size)
        assertEquals(3, plan.skipped.invalidMeasurements)
    }

    @Test
    fun `a body tracker file whose every date is unreadable is malformed`() {
        val result = read("Date,Time,Measurement,Value,Unit\nlast week,07:10,Bodyweight,80,kgs\n,07:10,Waist,80,cm\n")

        assertTrue(result.toString(), result is ParseResult.Malformed)
    }

    // ------------------------------------------------------------------ damaged files

    @Test
    fun `a bad row costs that row, and a bad number or unit costs its set, which the planner then counts`() {
        val workout = parsed("fitnotes-damaged.csv").workouts.single()

        // "yesterday", the day-first date, 30 February and the row with no exercise are lost.
        assertEquals(millis("2026-03-02T12:00:00Z"), workout.startedAt)
        assertEquals(
            listOf(
                ImportedSet(reps = 5, weight = 80_000_000),
                // "heavy" is not a weight, and "stone" not a unit FitNotes writes.
                ImportedSet(),
                ImportedSet(),
                // A row cut short after its reps.
                ImportedSet(reps = 5, weight = 80_000_000),
            ),
            workout.line(0).sets,
        )
        // "leagues" is not a unit, and "a while" not a time.
        assertEquals(listOf(ImportedSet(), ImportedSet()), workout.line(1).sets)
    }

    @Test
    fun `the sets a damaged file loses are counted in the report, not dropped in silence`() {
        val plan = ImportPlanner.plan(parsed("fitnotes-damaged.csv"), ImporterFixtures.freshInstall(plannedNow))

        assertEquals(4, plan.skipped.invalidSets)
        assertEquals(2, plan.setCount)
    }

    @Test
    fun `a file whose every row is unreadable is malformed`() {
        val result = read(header + "last week,Deadlift,Back,140,kgs,3,,,,\"\"\n2026-10-05,,Back,140,kgs,3,,,,\"\"\n")

        assertTrue(result.toString(), result is ParseResult.Malformed)
    }

    @Test
    fun `CSV that breaks past the header is malformed, naming the line`() {
        val result = read(
            header +
                "2026-10-05,Deadlift,Back,140,kgs,3,,,,\"\"\n" +
                "2026-10-05,Deadlift,Back,140,kgs,3,,,,\"never closed\n",
        )

        assertTrue(result.toString(), result is ParseResult.Malformed && "line 3" in result.reason)
    }

    @Test
    fun `hostile text in names and comments is carried as text`() {
        val csv = header +
            "2026-10-05,'; DROP TABLE session; --,Chest,80,kgs,5,,,,\"<script>\u0000</script>\"\n" +
            "2026-10-05,Bench Press‮,Chest,80,kgs,5,,,,\"\"\"quoted\"\" 💪\"\n"

        val workout = parsedText(csv).workouts.single()
        assertEquals(listOf("'; DROP TABLE session; --", "Bench Press‮"), workout.names())
        assertEquals("<script>\u0000</script>", workout.line(0).sets.single().notes)
        assertEquals("\"quoted\" 💪", workout.line(1).sets.single().notes)
    }

    // ------------------------------------------------------------------ through the planner

    @Test
    fun `a parsed export plans cleanly against a fresh install, onto the catalogue's own exercises`() {
        val plan = ImportPlanner.plan(parsed("fitnotes-export.csv"), ImporterFixtures.freshInstall(plannedNow))
        val catalogue = ImporterFixtures.catalogue.associateBy { it.name }
        fun existing(name: String) = ExerciseRef.Existing(catalogue.getValue(name).id)

        assertTrue(plan.skipped.toString(), plan.skipped.isEmpty)
        assertEquals(3, plan.workouts.size)
        val (push, legs, pull) = plan.workouts

        assertEquals(
            listOf(
                "Barbell Bench Press - Medium Grip",
                "Side Lateral Raise",
                "Face Pull",
                "Pullups",
                "Triceps Pushdown - Rope Attachment",
            ).map(::existing),
            push.exercises.take(5).map { it.exercise },
        )
        assertEquals(listOf("Warm-up", null, "Felt strong, RPE 8"), push.exercises[0].sets.map { it.notes })
        assertEquals(
            listOf(
                "Barbell Squat",
                "Romanian Deadlift",
                "Leg Extensions",
                "Seated Calf Raise",
                "Running, Treadmill",
                "Bicycling",
            )
                .map(::existing),
            legs.exercises.map { it.exercise },
        )
        assertEquals(existing("Barbell Deadlift"), pull.exercises[0].exercise)
        assertEquals(existing("Rowing, Stationary"), pull.exercises[2].exercise)
        // No duration in the file: two minutes a set from noon.
        assertEquals(push.startedAt + 10 * ImportPlanner.ESTIMATED_SECONDS_PER_SET * 1_000, push.finishedAt)

        // The timed plank (the catalogue's is rep-counted), the user's own curl and stair climb
        // are created once each, with the muscle their category names.
        val created = plan.newExercises.associate { it.exercise.name to (it.exercise.type to it.primaryMuscle?.name) }
        assertEquals(
            mapOf(
                "Plank" to (ExerciseType.ISOMETRIC to "ABDOMINALS"),
                "Curl, EZ Bar" to (ExerciseType.STRENGTH to "BICEPS"),
                "Stair Climb" to (ExerciseType.ENDURANCE to null),
            ),
            created,
        )
    }

    @Test
    fun `importing the same file again is a duplicate of every workout`() {
        val history = parsed("fitnotes-export.csv")
        val first = ImportPlanner.plan(history, ImporterFixtures.freshInstall(plannedNow))

        val again = ImportPlanner.plan(
            history,
            ImporterFixtures.freshInstall(plannedNow).copy(sessionStarts = first.workouts.map { it.startedAt }.toSet()),
        )
        assertEquals(3, again.skipped.duplicateWorkouts)
        assertTrue(again.workouts.isEmpty())
    }

    @Test
    fun `a day imported before its noon is refused until an import after noon adds it`() {
        val history = parsed("fitnotes-export.csv")

        val morning = ImportPlanner.plan(history, ImporterFixtures.freshInstall(millis("2026-10-09T09:00:00Z")))
        assertEquals(2, morning.workouts.size)
        assertEquals(1, morning.skipped.invalidWorkouts)

        val evening = ImportPlanner.plan(
            history,
            ImporterFixtures.freshInstall(millis("2026-10-09T20:00:00Z"))
                .copy(sessionStarts = morning.workouts.map { it.startedAt }.toSet()),
        )
        assertEquals(listOf(millis("2026-10-09T12:00:00Z")), evening.workouts.map { it.startedAt })
        assertEquals(2, evening.skipped.duplicateWorkouts)
    }

    @Test
    fun `a body tracker export plans into measurements, and an import run twice writes them once`() {
        val history = parsed("fitnotes-body-tracker.csv")
        val first = ImportPlanner.plan(history, ImporterFixtures.freshInstall(plannedNow))

        assertTrue(first.skipped.toString(), first.skipped.isEmpty)
        assertEquals(4, first.measurements.size)

        val again = ImportPlanner.plan(
            history,
            ImporterFixtures.freshInstall(
                plannedNow,
            ).copy(measurementTimes = first.measurements.map { it.createdAt }.toSet()),
        )
        assertEquals(4, again.skipped.duplicateMeasurements)
        assertTrue(again.measurements.isEmpty())
    }
}
