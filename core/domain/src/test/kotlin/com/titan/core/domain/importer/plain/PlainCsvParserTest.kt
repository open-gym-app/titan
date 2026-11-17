package com.titan.core.domain.importer.plain

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
import com.titan.core.domain.model.SetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * The plain CSV parser over the format `docs/import-csv.md` specifies: its example files, a file
 * that fills every column, a spreadsheet's re-save of one, a measurement file and a damaged one,
 * and the files it has to turn away.
 */
class PlainCsvParserTest {

    private val parser = PlainCsvParser()

    private fun parsed(
        sample: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.resource("plain/$sample"), context)

    private fun parsedText(
        text: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.text(text), context)

    private fun read(text: String): ParseResult = ImporterFixtures.read(parser, ImporterFixtures.text(text))

    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()

    private fun kg(kilograms: Double) = (kilograms * 1_000_000).toLong()

    private fun ImportedWorkout.names() = exercises.map { it.exercise.name }

    /** The sets of the only exercise of the only workout [text] holds. */
    private fun onlySets(
        text: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): List<ImportedSet> = parsedText(text, context).workouts.single().exercises.single().sets

    private val plannedNow = millis("2026-11-16T00:00:00Z")

    // Pound figures as Titan stores them.
    private val lb185 = 83_914_588L
    private val lb225 = 102_058_283L

    // ------------------------------------------------------------------ recognising the file

    @Test
    fun `anything that is not a plain CSV file is not recognised`() {
        listOf(
            ImporterFixtures.bytes(ByteArray(0)),
            ImporterFixtures.text("hello world\nthis is a shopping list\n"),
            ImporterFixtures.bytes(Random(seed = 254).nextBytes(4_096)),
            // The obvious names, which FitNotes' and Fitbod's exports start with.
            ImporterFixtures.text("Date,Exercise,Reps,Weight\n2026-11-02,Squat,5,100\n"),
            // A workout file's columns without the exercise, or without the date.
            ImporterFixtures.text("workout_date,reps,weight\n2026-11-02,5,100\n"),
            ImporterFixtures.text("exercise,date,reps\nSquat,2026-11-02,5\n"),
            // A measurement file without its date.
            ImporterFixtures.text("date,weight,waist\n2026-11-02,80,85\n"),
            // A first record that is not CSV at all.
            ImporterFixtures.text("\"workout_date,exercise\n2026-11-02,Squat\n"),
        ).forEachIndexed { index, source ->
            assertEquals("source $index", ParseResult.NotRecognised, ImporterFixtures.read(parser, source))
        }
    }

    @Test
    fun `no Strong, Hevy, FitNotes, JEFIT, Fitbod or StrongLifts export is a plain CSV file`() {
        listOf(
            "strong/strong-ios.csv",
            "strong/strong-android.csv",
            "strong/strong-unit-columns.csv",
            "strong/strong-early.csv",
            "strong/strong-damaged.csv",
            "strong/strong-header-only.csv",
            "strong/strong-unclosed-quote.csv",
            "hevy/hevy-workouts.csv",
            "hevy/hevy-workouts-web.csv",
            "hevy/hevy-workouts-de.csv",
            "hevy/hevy-measurements.csv",
            "hevy/hevy-damaged.csv",
            "fitnotes/fitnotes-export.csv",
            "fitnotes/fitnotes-export-legacy.csv",
            "fitnotes/fitnotes-ios.csv",
            "fitnotes/fitnotes-body-tracker.csv",
            "fitnotes/fitnotes-damaged.csv",
            "fitnotes/FitNotes_Backup.fitnotes",
            "jefit/jefit-export.csv",
            "jefit/jefit-export-2024.csv",
            "jefit/jefit-damaged.csv",
            "fitbod/fitbod-export.csv",
            "fitbod/fitbod-export-resaved.csv",
            "fitbod/fitbod-support-export.csv",
            "fitbod/fitbod-damaged.csv",
            "stronglifts/stronglifts-export.csv",
            "stronglifts/stronglifts-export-lb.csv",
            "stronglifts/stronglifts-export-semicolon.csv",
            "stronglifts/stronglifts-spreadsheet.csv",
            "stronglifts/stronglifts-spreadsheet-us.csv",
            "stronglifts/stronglifts-damaged.csv",
        ).forEach { sample ->
            assertEquals(
                sample,
                ParseResult.NotRecognised,
                ImporterFixtures.read(parser, ImporterFixtures.resource(sample)),
            )
        }
    }

    @Test
    fun `a file with a header and no rows is an empty history, not a broken one`() {
        assertTrue(parsedText("workout_date,exercise,reps\n").isEmpty)
        assertTrue(parsedText("measurement_date,weight\n").isEmpty)
    }

    @Test
    fun `column names are read whatever their case, spacing and order, and other columns are ignored`() {
        val expected = listOf(ImportedSet(reps = 5, weight = kg(100.0), rpe = 80))
        listOf(
            "workout_date,exercise,reps,weight,rpe\n2026-11-02,Squat,5,100,8",
            "Workout Date,Exercise,Reps,Weight,RPE\n2026-11-02,Squat,5,100,8",
            " WORKOUT-DATE ,  exercise ,REPS,Weight,Rpe (1-10)\n2026-11-02,Squat,5,100,8",
            "RPE,My Own Column,Weight,Reps,Exercise,Workout.Date,weight_total\n8,anything,100,5,Squat,2026-11-02,500",
            "workout_date\texercise\treps\tweight\trpe\n2026-11-02\tSquat\t5\t100\t8",
            "workout_date|exercise|reps|weight|rpe\n2026-11-02|Squat|5|100|8",
        ).forEach { text -> assertEquals(text, expected, onlySets(text)) }
    }

    @Test
    fun `a column whose header names no unit Titan reads is malformed`() {
        listOf(
            "workout_date,exercise,weight (stone)" to "weight (stone)",
            "workout_date,exercise,distance (parsecs)" to "distance (parsecs)",
            "workout_date,exercise,duration (days)" to "duration (days)",
            "workout_date,exercise,rest (beats)" to "rest (beats)",
            "workout_date,exercise,workout_duration (fortnights)" to "workout_duration (fortnights)",
            "measurement_date,weight (stone)" to "weight (stone)",
            "measurement_date,waist (furlongs)" to "waist (furlongs)",
        ).forEach { (columns, column) ->
            val result = read("$columns\n2026-11-02,Squat,1")
            assertTrue(result.toString(), result is ParseResult.Malformed && "\"$column\"" in result.reason)
        }
    }

    // ------------------------------------------------------------------ a workout file

    @Test
    fun `every workout column is read, a row per set, a workout per start`() {
        val history = parsed("plain-workouts.csv")

        assertEquals(listOf("Upper", "Run", "Legs", "Evening"), history.workouts.map { it.name })
        assertEquals(
            listOf("2026-10-05T18:00:00Z", "2026-10-07T05:00:00Z", "2026-10-09T12:00:00Z", "2026-10-09T18:30:00Z")
                .map(::millis),
            history.workouts.map { it.startedAt },
        )
        val (upper, run, legs) = history.workouts
        val evening = history.workouts[3]
        assertEquals(4_200L, upper.durationSeconds)
        assertEquals("Good session", upper.notes)
        assertEquals(450, upper.calories)
        assertEquals(4, upper.rating)
        assertEquals(
            listOf(
                "Bench Press (Barbell)",
                "Chest-Supported Row",
                "Face Pull",
                "Assisted Pull-Up",
                "Push-Up",
                "Bench Press (Barbell)",
            ),
            upper.names(),
        )

        assertEquals(2_100L, run.durationSeconds)
        assertEquals(380, run.calories)
        assertNull(run.rating)
        assertEquals(
            listOf(
                ImportedSet(
                    reps = null,
                    distance = 520_000,
                    duration = 1_890,
                    heartRateAvg = 152,
                    heartRateMax = 171,
                    notes = "Negative split",
                ),
                ImportedSet(
                    setType = SetType.INTERVAL,
                    distance = 40_000,
                    duration = 85,
                    heartRateAvg = 174,
                    heartRateMax = 181,
                ),
            ),
            run.exercises.single().sets,
        )

        // Every different workout note is kept; a workout with no duration is left to the planner.
        assertEquals("Knee felt fine\nShort on time", legs.notes)
        assertNull(legs.durationSeconds)
        assertEquals(listOf("Barbell Squat", "Plank", "Hip Thrust (Barbell)"), legs.names())
        assertEquals(listOf(ImportedSet(duration = 60)), legs.exercises[1].sets)
        // A row with no figure names its exercise and records no set.
        assertEquals(emptyList<ImportedSet>(), legs.exercises[2].sets)

        assertEquals(
            listOf(ImportedSet(reps = 10, weight = kg(200.0), restAfter = 120)),
            evening.exercises.single().sets,
        )
    }

    @Test
    fun `a set keeps its type, figures, rest and note, and a weight its cell's own unit`() {
        val bench = parsed("plain-workouts.csv").workouts.first().exercises
        assertEquals(
            listOf(
                ImportedSet(setType = SetType.WARMUP, reps = 10, weight = kg(40.0), restAfter = 90),
                ImportedSet(reps = 5, weight = kg(82.5), rpe = 80, restAfter = 180),
                // `82.5kg` beside a `weight_unit` of `lb`: the cell says what it is.
                ImportedSet(
                    setType = SetType.FAILURE,
                    reps = 3,
                    weight = kg(82.5),
                    rpe = 100,
                    notes = "Spotter helped",
                ),
            ),
            bench.first().sets,
        )
        assertEquals("Pause the first rep", bench.first().notes)
        assertEquals(listOf(ImportedSet(setType = SetType.DROP_SET, reps = 8, weight = kg(60.0))), bench.last().sets)
        // An assisted exercise's weight is its counterweight; a bodyweight set's zero is kept.
        assertEquals(listOf(ImportedSet(reps = 8)), bench[3].sets)
        assertEquals(listOf(ImportedSet(reps = 20, weight = 0)), bench[4].sets)
    }

    @Test
    fun `a superset written set by set is one exercise per movement, sharing one group`() {
        val upper = parsed("plain-workouts.csv").workouts.first()
        val (row, facePull) = upper.exercises.subList(1, 3)

        assertEquals(List(2) { ImportedSet(reps = 12, weight = kg(30.0)) }, row.sets)
        assertEquals(
            listOf(
                ImportedSet(reps = 15, weight = kg(20.0)),
                ImportedSet(reps = 15, weight = kg(20.0), restAfter = 120),
            ),
            facePull.sets,
        )
        assertTrue(row.supersetGroup != null && row.supersetGroup == facePull.supersetGroup)
        assertTrue(upper.exercises.filterNot { it in listOf(row, facePull) }.all { it.supersetGroup == null })
    }

    @Test
    fun `supersets are runs of one label, and an exercise outside one runs on only while it repeats`() {
        val workout = parsedText(
            listOf(
                "workout_date,exercise,superset,reps",
                "2026-11-02,Curl,a,10",
                "2026-11-02,Pushdown,A,10",
                "2026-11-02,Curl,A,10",
                "2026-11-02,Squat,,5",
                "2026-11-02,Squat,,5",
                "2026-11-02,Curl,A,10",
                "2026-11-02,Pushdown,b,10",
                "2026-11-02,Squat,,5",
            ).joinToString("\n"),
        ).workouts.single()

        assertEquals(listOf("Curl", "Pushdown", "Squat", "Curl", "Pushdown", "Squat"), workout.names())
        assertEquals(listOf(2, 1, 2, 1, 1, 1), workout.exercises.map { it.sets.size })
        val groups = workout.exercises.map { it.supersetGroup }
        // `a` and `A` are one label. The same label later, after a break, is another superset, and
        // so is a new label straight after it; a label alone on one exercise is for the planner to undo.
        assertEquals(groups[0], groups[1])
        assertNull(groups[2])
        assertTrue(groups[3] != null && groups[3] != groups[0])
        assertTrue(groups[4] != null && groups[4] != groups[3])
        assertNull(groups[5])
    }

    @Test
    fun `exercises carry their equipment and muscle, and catalogue names from the common naming styles`() {
        val byName = parsed("plain-workouts.csv").exercises.associateBy { it.name }

        assertEquals(10, byName.size)
        assertEquals(
            ImportedExercise(
                name = "Bench Press (Barbell)",
                catalogueName = "Barbell Bench Press - Medium Grip",
                equipment = EquipmentType.BARBELL,
                primaryMuscle = "CHEST",
            ),
            byName["Bench Press (Barbell)"],
        )
        assertEquals(
            ImportedExercise(
                name = "Chest-Supported Row",
                equipment = EquipmentType.DUMBBELL,
                primaryMuscle = "MIDDLE_BACK",
            ),
            byName["Chest-Supported Row"],
        )
        assertEquals(EquipmentType.CABLE, byName.getValue("Face Pull").equipment)
        assertEquals("Pushups", byName.getValue("Push-Up").catalogueName)
        // The equipment at the front of the name.
        assertEquals(EquipmentType.BARBELL, byName.getValue("Barbell Squat").equipment)
        assertEquals(EquipmentType.MACHINE, byName.getValue("Leg Press").equipment)
    }

    @Test
    fun `equipment, muscle and set type are read however they are written, and unknown ones are not guessed`() {
        val history = parsedText(
            listOf(
                "workout_date,exercise,equipment,muscle,set_type,reps",
                "2026-11-02,Curl,EZ_CURL_BAR,Lower-Back,Warm-Up,10",
                "2026-11-02,Carry,Other,,DROP_SET,10",
                "2026-11-02,Deadlift,trap bar,rear delts,w,10",
                "2026-11-02,Deadlift,trap bar,,D,10",
                "2026-11-02,Deadlift,trap bar,,F,10",
                "2026-11-02,Deadlift,trap bar,,Drop Set,10",
                "2026-11-02,Deadlift,trap bar,,working,10",
                "2026-11-02,Deadlift,trap bar,,cluster,10",
                "2026-11-02,Dumbbell Row,barbell,,,10",
            ).joinToString("\n"),
        )
        val byName = history.exercises.associateBy { it.name }

        assertEquals(EquipmentType.EZ_CURL_BAR, byName.getValue("Curl").equipment)
        assertEquals("LOWER_BACK", byName.getValue("Curl").primaryMuscle)
        assertEquals(EquipmentType.OTHER, byName.getValue("Carry").equipment)
        assertNull(byName.getValue("Deadlift").equipment)
        assertEquals("REAR_DELTS", byName.getValue("Deadlift").primaryMuscle)
        // A column that says the equipment says it over the name.
        assertEquals(EquipmentType.BARBELL, byName.getValue("Dumbbell Row").equipment)
        assertEquals(
            listOf(SetType.WARMUP, SetType.DROP_SET, SetType.WARMUP, SetType.DROP_SET, SetType.FAILURE) +
                listOf(SetType.DROP_SET, SetType.NORMAL, SetType.NORMAL, SetType.NORMAL),
            history.workouts.single().exercises.flatMap { line -> line.sets.map { it.setType } },
        )
    }

    @Test
    fun `the same exercise spelt two ways is one, and two equipments are two`() {
        val workout = parsedText(
            "workout_date,exercise,equipment,reps\n" +
                "2026-11-02,Bench Press,,5\n2026-11-02,bench  press,,5\n" +
                "2026-11-02,Bench Press,dumbbell,5\n",
        ).workouts.single()

        assertEquals(listOf(2, 1), workout.exercises.map { it.sets.size })
        assertEquals("Bench Press", workout.exercises.first().exercise.name)
        assertEquals(EquipmentType.DUMBBELL, workout.exercises.last().exercise.equipment)
    }

    @Test
    fun `rows sharing a start are one workout wherever they are, the first name and figures winning`() {
        val history = parsedText(
            listOf(
                "workout_date,workout_name,workout_rating,workout_calories,exercise,reps",
                "2026-11-02,Morning,,,Squat,5",
                "2026-11-03,Other day,3,,Squat,5",
                "2026-11-02,Evening,5,300,Squat,5",
                "2026-11-02,,4,200,Squat,5",
            ).joinToString("\n"),
        )

        assertEquals(listOf("Morning", "Other day"), history.workouts.map { it.name })
        val day = history.workouts.first()
        assertEquals(5, day.rating)
        assertEquals(300, day.calories)
        // The third row comes back to the squat after another workout's: still one exercise of this one.
        assertEquals(listOf(3), day.exercises.map { it.sets.size })
    }

    // ------------------------------------------------------------------ dates, times and units

    @Test
    fun `a workout starts at its own time, at its time column's, or at noon, in the user's zone`() {
        val paris = ImporterFixtures.context("Europe/Paris")
        val starts = parsedText(
            listOf(
                "workout_date,workout_time,exercise,reps",
                "2026-11-02,,Squat,5",
                "2026-11-03 18:30,,Squat,5",
                "2026-11-04T18:30:15,,Squat,5",
                "2026-11-05T18:30:00Z,,Squat,5",
                "2026-11-06 00:00,6:30 PM,Squat,5",
                "2026-11-07T00:00:00,18:30:00,Squat,5",
                "2026/11/08,,Squat,5",
            ).joinToString("\n"),
            paris,
        ).workouts.map { it.startedAt }

        assertEquals(
            listOf(
                "2026-11-02T11:00:00Z",
                "2026-11-03T17:30:00Z",
                "2026-11-04T17:30:15Z",
                "2026-11-05T18:30:00Z",
                "2026-11-06T17:30:00Z",
                "2026-11-07T17:30:00Z",
                "2026-11-08T11:00:00Z",
            ).map(::millis),
            starts,
        )
    }

    @Test
    fun `a spreadsheet's re-save reads the same, its dates in the order the file proves`() {
        val history = parsed("plain-workouts-spreadsheet.csv")
        val (first, second) = history.workouts

        assertEquals(millis("2026-11-02T18:30:00Z"), first.startedAt)
        assertEquals(millis("2026-11-15T18:30:00Z"), second.startedAt)
        assertEquals(listOf("Bench Press (Barbell)", "Treadmill Run"), first.names())
        assertEquals(
            listOf(
                ImportedSet(setType = SetType.WARMUP, reps = 5, weight = lb185, rpe = 85),
                ImportedSet(reps = 5, weight = lb185, rpe = 90),
            ),
            first.exercises[0].sets,
        )
        // 3.1 mi and 25.5 minutes, in the units the headers name.
        assertEquals(listOf(ImportedSet(distance = 498_897, duration = 1_530)), first.exercises[1].sets)
        assertEquals(
            listOf(ImportedSet(reps = 5, weight = lb225, notes = "Deep, slow")),
            second.exercises.single().sets,
        )
    }

    @Test
    fun `year-last dates read month first when the file proves it, and not at all when it cannot`() {
        assertEquals(
            listOf("2026-11-02T12:00:00Z", "2026-11-25T12:00:00Z").map(::millis),
            parsedText(
                "workout_date,exercise,reps\n11/2/2026,Squat,5\n11/25/2026,Squat,5",
            ).workouts.map { it.startedAt },
        )
        val undecided = read("workout_date,exercise,reps\n02/11/2026,Squat,5\n03/11/2026,Squat,5")
        assertTrue(undecided.toString(), undecided is ParseResult.Malformed)
    }

    @Test
    fun `figures are in their row's unit, else the header's, else the user's`() {
        assertEquals(
            listOf(
                ImportedSet(reps = 5, weight = lb225, distance = 160_934),
                ImportedSet(reps = 5, weight = kg(100.0), distance = 100_000),
            ),
            onlySets(
                "workout_date,exercise,reps,weight,weight_unit,distance,distance_unit\n" +
                    "2026-11-02,Sled,5,225,,1,\n2026-11-02,Sled,5,100,kg,1,km",
                IMPERIAL_UTC,
            ),
        )
        assertEquals(
            listOf(ImportedSet(reps = 5, weight = lb225, distance = 30_480), ImportedSet(reps = 5, weight = kg(100.0))),
            onlySets(
                "workout_date,exercise,reps,weight_lb,distance (ft),weight_unit\n" +
                    "2026-11-02,Sled,5,225,1000,\n2026-11-02,Sled,5,100,,kg",
            ),
        )
        // A set's time and rest are seconds and a workout's minutes, unless the header says otherwise.
        val workout = parsedText(
            "workout_date,workout_duration,exercise,duration,rest\n2026-11-02,45,Plank,60,90\n" +
                "2026-11-03,1:05:00,Plank,1:30,1h\n",
        ).workouts
        assertEquals(listOf(2_700L, 3_900L), workout.map { it.durationSeconds })
        assertEquals(
            listOf(ImportedSet(duration = 60, restAfter = 90), ImportedSet(duration = 90, restAfter = 3_600)),
            workout.flatMap { it.exercises.single().sets },
        )
        assertEquals(
            listOf(ImportedSet(duration = 120, restAfter = 3)),
            onlySets("workout_date,exercise,duration (min),rest_min\n2026-11-02,Plank,2,0.05"),
        )
    }

    // ------------------------------------------------------------------ a measurement file

    @Test
    fun `every measurement column is read, a row per measurement, in the units each row names`() {
        val measurements = parsed("plain-measurements.csv").bodyMeasurements

        val first = millis("2026-10-01T07:10:00Z")
        val second = millis("2026-10-15T12:00:00Z")
        val third = millis("2026-11-01T06:00:00Z")
        assertEquals(
            listOf(
                BodyMeasurement(
                    weight = kg(82.4),
                    bodyFat = 185,
                    neck = 380,
                    shoulders = 1_180,
                    chest = 1_040,
                    waist = 860,
                    hips = 980,
                    arm = 370,
                    forearm = 300,
                    thigh = 580,
                    calf = 380,
                    notes = "Morning, fasted",
                    createdAt = first,
                    updatedAt = first,
                ),
                BodyMeasurement(
                    weight = 82_327_015,
                    bodyFat = 181,
                    neck = 381,
                    shoulders = 1_181,
                    chest = 1_041,
                    waist = 851,
                    hips = 978,
                    arm = 368,
                    forearm = 305,
                    thigh = 584,
                    calf = 381,
                    createdAt = second,
                    updatedAt = second,
                ),
                BodyMeasurement(weight = kg(82.0), createdAt = third, updatedAt = third),
            ),
            measurements,
        )
    }

    @Test
    fun `a measurement's figures are in its header's units, else the user's`() {
        val noon = millis("2026-11-02T12:00:00Z")
        assertEquals(
            listOf(BodyMeasurement(weight = lb225, waist = 864, chest = 1_000, createdAt = noon, updatedAt = noon)),
            parsedText(
                "measurement_date,weight,waist,chest (cm)\n2026-11-02,225,34,100",
                IMPERIAL_UTC,
            ).bodyMeasurements,
        )
        assertEquals(
            listOf(BodyMeasurement(weight = kg(80.0), waist = 850, createdAt = noon, updatedAt = noon)),
            parsedText("Measurement Date,Weight_kg,Waist_mm\n2026-11-02,80,850", IMPERIAL_UTC).bodyMeasurements,
        )
    }

    @Test
    fun `a measurement with a bad figure or unit is passed on empty, and one without a date is left out`() {
        val noon = millis("2026-11-02T12:00:00Z")
        val empty = BodyMeasurement(createdAt = noon, updatedAt = noon)
        val measurements = parsedText(
            listOf(
                "measurement_date,weight,weight_unit,body_fat,waist,length_unit",
                "2026-11-02,heavy,,,,",
                "2026-11-02,80,stone,,,",
                "2026-11-02,,,lots,,",
                "2026-11-02,,,,85,furlongs",
                "2026-11-02,,,,slim,",
                "2026-11-02,,,,,",
                "last week,80,,,,",
            ).joinToString("\n"),
        ).bodyMeasurements

        assertEquals(List(6) { empty }, measurements)
        val nothing = read("measurement_date,weight\nlast week,80\nsoon,81")
        assertTrue(nothing.toString(), nothing is ParseResult.Malformed && "2 rows" in nothing.reason)
    }

    // ------------------------------------------------------------------ damaged files

    @Test
    fun `a bad row costs that row, and a bad figure costs its set, which the planner counts`() {
        val workout = parsed("plain-damaged.csv").workouts.single()
        val (bench, rowing) = workout.exercises

        assertEquals(4, workout.rating)
        assertEquals(
            listOf(
                ImportedSet(reps = 5, weight = kg(100.0), rpe = 80),
                ImportedSet(),
                ImportedSet(),
                ImportedSet(),
                // An RPE, a heart rate or a rating that is not a number costs nothing but itself.
                ImportedSet(reps = 5, weight = kg(100.0)),
            ),
            bench.sets,
        )
        assertEquals(listOf(ImportedSet(), ImportedSet(), ImportedSet(distance = 200_000, duration = 480)), rowing.sets)

        val plan = ImportPlanner.plan(parsed("plain-damaged.csv"), ImporterFixtures.freshInstall(plannedNow))
        assertEquals(5, plan.skipped.invalidSets)
        assertEquals(3, plan.setCount)
    }

    @Test
    fun `a file whose every row has no date or exercise that reads is malformed`() {
        val result = read("workout_date,exercise,reps\nyesterday,Squat,5\n2026-11-02,,5\n")
        assertTrue(result.toString(), result is ParseResult.Malformed && "2 rows" in result.reason)
    }

    @Test
    fun `CSV that breaks past the header is malformed, naming the line`() {
        val result = read("workout_date,exercise,notes\n2026-11-02,Squat,fine\n2026-11-02,Squat,\"never closed")
        assertTrue(result.toString(), result is ParseResult.Malformed && "line 3" in result.reason)
    }

    @Test
    fun `hostile text is carried as text and hostile figures are refused rather than believed`() {
        val history = parsedText(
            listOf(
                "workout_date,workout_name,exercise,reps,weight,distance,workout_duration,notes,muscle",
                "2026-11-02,\"'; DROP TABLE session; --\",\"<script>\"\"x\"\"</script>\",NaN,1e5,,,\"<b>note</b>\",../../etc",
                "2026-11-02,,Bench Press‮\u0000,99999999999999999999,99999999999999999999,99999999999999999999,99999999999999999999,,",
                "+999999999-12-31,,Squat,5,60,,,,",
            ).joinToString("\n"),
        )

        val workout = history.workouts.single()
        val (injection, overflow) = workout.exercises
        assertEquals("'; DROP TABLE session; --", workout.name)
        assertEquals("<script>\"x\"</script>", injection.exercise.name)
        assertEquals("ETC", injection.exercise.primaryMuscle)
        assertEquals(listOf(ImportedSet()), injection.sets)
        assertEquals("Bench Press‮\u0000", overflow.exercise.name)
        // Read as written and clamped; the planner refuses what Titan's bounds do not allow.
        assertEquals(Int.MAX_VALUE, overflow.sets.single().reps)
        assertEquals(Long.MAX_VALUE, overflow.sets.single().weight)
        assertEquals(Long.MAX_VALUE, overflow.sets.single().distance)
        assertEquals(Long.MAX_VALUE, workout.durationSeconds)
    }

    // ------------------------------------------------------------------ through the planner

    @Test
    fun `a parsed file plans cleanly against a fresh install, onto the catalogue's own exercises`() {
        val plan = ImportPlanner.plan(parsed("plain-workouts.csv"), ImporterFixtures.freshInstall(plannedNow))
        val catalogue = ImporterFixtures.catalogue.associateBy { it.name }
        fun existing(name: String) = ExerciseRef.Existing(catalogue.getValue(name).id)

        assertTrue(plan.skipped.toString(), plan.skipped.isEmpty)
        assertEquals(4, plan.workouts.size)
        assertEquals(15, plan.setCount)
        val (upper, run, legs) = plan.workouts
        val evening = plan.workouts[3]
        assertEquals(existing("Barbell Bench Press - Medium Grip"), upper.exercises.first().exercise)
        assertEquals(existing("Face Pull"), upper.exercises[2].exercise)
        assertEquals(existing("Pushups"), upper.exercises[4].exercise)
        assertEquals(existing("Barbell Squat"), legs.exercises.first().exercise)
        assertEquals(existing("Leg Press"), evening.exercises.single().exercise)
        // The superset survives the planner's rules, and the line with no sets does not.
        assertEquals(upper.exercises[1].supersetGroup, upper.exercises[2].supersetGroup)
        assertEquals(2, legs.exercises.size)
        assertEquals(70 * 60 * 1_000L, upper.finishedAt - upper.startedAt)
        assertEquals(450, upper.calories)
        assertEquals(1, run.exercises.size)

        val created = plan.newExercises.associate { new ->
            new.exercise.name to Triple(new.exercise.type, new.exercise.equipmentType, new.primaryMuscle?.name)
        }
        assertEquals(
            mapOf(
                "Chest-Supported Row" to Triple(ExerciseType.STRENGTH, EquipmentType.DUMBBELL, "MIDDLE_BACK"),
                "Assisted Pull-Up" to Triple(ExerciseType.STRENGTH, EquipmentType.OTHER, null),
                "Running" to Triple(ExerciseType.ENDURANCE, EquipmentType.OTHER, null),
                "Plank" to Triple(ExerciseType.ISOMETRIC, EquipmentType.OTHER, null),
            ),
            created,
        )
    }

    @Test
    fun `importing the same file again is a duplicate of every workout and measurement`() {
        listOf("plain-workouts.csv", "plain-measurements.csv").forEach { sample ->
            val history = parsed(sample)
            val first = ImportPlanner.plan(history, ImporterFixtures.freshInstall(plannedNow))
            val again = ImportPlanner.plan(
                history,
                ImporterFixtures.freshInstall(plannedNow).copy(
                    sessionStarts = first.workouts.map { it.startedAt }.toSet(),
                    measurementTimes = first.measurements.map { it.createdAt }.toSet(),
                ),
            )
            assertEquals(sample, first.workouts.size, again.skipped.duplicateWorkouts)
            assertEquals(sample, first.measurements.size, again.skipped.duplicateMeasurements)
            assertTrue(sample, again.workouts.isEmpty() && again.measurements.isEmpty())
        }
    }

    @Test
    fun `the example files the documentation publishes import cleanly`() {
        val workouts = ImporterFixtures.parsed(parser, ImporterFixtures.document("import-csv-workouts.csv"))
        val workoutPlan = ImportPlanner.plan(workouts, ImporterFixtures.freshInstall(plannedNow))
        assertTrue(workoutPlan.skipped.toString(), workoutPlan.skipped.isEmpty)
        assertEquals(3 to 20, workoutPlan.workouts.size to workoutPlan.setCount)
        assertEquals(listOf("Push", "Morning run", "Legs"), workoutPlan.workouts.map { it.name })
        val push = workouts.workouts.first()
        assertEquals(
            listOf("Bench Press (Barbell)", "Incline Dumbbell Press", "Side Lateral Raise", "Triceps Pushdown"),
            push.names(),
        )
        assertEquals(push.exercises[1].supersetGroup, push.exercises[2].supersetGroup)
        // Only the plank, held for time where the catalogue's is counted in reps, is not in Titan's library.
        assertEquals(listOf("Plank"), workoutPlan.newExercises.map { it.exercise.name })

        val measurements = ImporterFixtures.parsed(parser, ImporterFixtures.document("import-csv-measurements.csv"))
        val measurementPlan = ImportPlanner.plan(measurements, ImporterFixtures.freshInstall(plannedNow))
        assertTrue(measurementPlan.skipped.toString(), measurementPlan.skipped.isEmpty)
        assertEquals(4, measurementPlan.measurements.size)
    }

    @Test
    fun `the other samples plan cleanly too`() {
        val plan = ImportPlanner.plan(
            parsed("plain-workouts-spreadsheet.csv"),
            ImporterFixtures.freshInstall(plannedNow),
        )
        assertTrue(plan.skipped.toString(), plan.skipped.isEmpty)
        assertEquals(2 to 4, plan.workouts.size to plan.setCount)
    }
}
