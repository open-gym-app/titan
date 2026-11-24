package com.titan.core.domain.importer.stronglifts

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
 * The StrongLifts parser over sample exports in both layouts the app has written — one row per
 * exercise, in kilograms, in pounds and with semicolons, and one row per workout, day first and
 * month first — and over the files it has to turn away or survive.
 */
class StrongLiftsParserTest {

    private val parser = StrongLiftsParser()

    private fun parsed(
        sample: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.resource("stronglifts/$sample"), context)

    private fun parsedText(
        text: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.text(text), context)

    private fun read(text: String): ParseResult = ImporterFixtures.read(parser, ImporterFixtures.text(text))

    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()

    private fun ImportedWorkout.names() = exercises.map { it.exercise.name }

    private fun kg(kilograms: Double) = (kilograms * 1_000_000).toLong()

    private fun sets(
        count: Int,
        reps: Int,
        weight: Long?,
    ) = List(count) { ImportedSet(reps = reps, weight = weight) }

    private val plannedNow = millis("2026-11-16T00:00:00Z")

    private fun weighIn(
        weight: Long,
        takenAt: Long,
    ) = BodyMeasurement(weight = weight, createdAt = takenAt, updatedAt = takenAt)

    private val header = "Date (yyyy/mm/dd),Workout,Workout Name,Body Weight (KG),Exercise,Sets×Reps,Sets×Time," +
        "Duration (hours),Start Time (h:mm),End Time (h:mm),Notes,Set 1 (Reps),Set 1 (KG),Set 2 (Reps),Set 2 (KG)"

    /** An export in the newer layout of [rows] under [columns]. */
    private fun export(
        vararg rows: String,
        columns: String = header,
    ): String = (listOf(columns) + rows).joinToString("\n")

    private val oldHeader = "Date,Note,Workout,Body Weight,Exercise 1,Weight (KG),Weight (LB),Set 1,Set 2,Set 3"

    /** The sets of the only exercise of the only workout [text] holds. */
    private fun onlySets(
        text: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): List<ImportedSet> = parsedText(text, context).workouts.single().exercises.single().sets

    // Pound figures as Titan stores them.
    private val lb45 = 20_411_657L
    private val lb255 = 115_666_054L
    private val lb135 = 61_234_970L
    private val lb95 = 43_091_275L
    private val lb35 = 15_875_733L
    private val lb140 = 63_502_932L
    private val lb65 = 29_483_504L
    private val lb200 = 90_718_474L

    // ------------------------------------------------------------------ recognising the file

    @Test
    fun `anything that is not a StrongLifts export is not recognised`() {
        listOf(
            ImporterFixtures.bytes(ByteArray(0)),
            ImporterFixtures.text("hello world\nthis is a shopping list\n"),
            ImporterFixtures.bytes(Random(seed = 253).nextBytes(4_096)),
            // The newer layout's columns without a set's reps, a date or an exercise.
            ImporterFixtures.text("Date (yyyy/mm/dd),Exercise,Set 1 (KG)\n2026/09/01,Squat,60\n"),
            ImporterFixtures.text("Workout,Exercise,Set 1 (Reps)\n1,Squat,5\n"),
            ImporterFixtures.text("Date (yyyy/mm/dd),Workout,Set 1 (Reps)\n2026/09/01,1,5\n"),
            // The older layout's columns without a weight, an exercise group or the workout.
            ImporterFixtures.text("Date,Note,Workout,Exercise 1,Set 1\n02/05/17,,A,Squat,5\n"),
            ImporterFixtures.text("Date,Note,Workout,Weight (KG),Set 1\n02/05/17,,A,50,5\n"),
            ImporterFixtures.text("Date,Note,Exercise 1,Weight (KG),Set 1\n02/05/17,,Squat,50,5\n"),
            // A first record that is not CSV at all.
            ImporterFixtures.text("\"$header\n"),
        ).forEachIndexed { index, source ->
            assertEquals("source $index", ParseResult.NotRecognised, ImporterFixtures.read(parser, source))
        }
    }

    @Test
    fun `no Strong, Hevy, FitNotes, JEFIT or Fitbod export is a StrongLifts export`() {
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
        ).forEach { sample ->
            assertEquals(
                sample,
                ParseResult.NotRecognised,
                ImporterFixtures.read(parser, ImporterFixtures.resource(sample)),
            )
        }
    }

    @Test
    fun `an export with no workouts in it is an empty history, not a broken file`() {
        assertEquals(ImportedHistory(), parsedText(header))
        assertEquals(ImportedHistory(), parsedText("$oldHeader\n\n"))
    }

    // ------------------------------------------------------------------ one row per exercise

    @Test
    fun `the rows sharing a start are one workout, two the same day are two, in the file's order`() {
        val workouts = parsed("stronglifts-export.csv").workouts

        assertEquals(
            listOf(
                millis("2026-09-01T18:05:00Z"),
                millis("2026-09-03T07:30:00Z"),
                millis("2026-09-03T21:50:00Z"),
                // No start time: noon.
                millis("2026-09-05T12:00:00Z"),
            ),
            workouts.map { it.startedAt },
        )
        assertEquals(listOf("Workout A", "Workout B", "Workout A", "Workout B"), workouts.map { it.name })
        assertEquals(listOf("Squat", "Bench Press", "Barbell Row"), workouts[0].names())
        assertEquals(listOf("Squat", "Overhead Press", "Deadlift", "Chinups"), workouts[1].names())
        assertEquals(listOf("Squat", "Dips", "Dumbbell Curl", "Planks"), workouts[2].names())
        // A row whose every set was skipped is no exercise of the workout.
        assertEquals(listOf("Front Squat", "Incline Bench Press"), workouts[3].names())
    }

    @Test
    fun `a workout lasts from its start to its end, or the recorded figure when it agrees to the minute`() {
        val workouts = parsed("stronglifts-export.csv").workouts

        assertEquals(
            listOf(
                // 0.9480556 h is 3,413 s, within a minute of 18:05 to 19:02.
                3_413L,
                // 0.8 h is three minutes off 07:30 to 08:15: the clock times win.
                2_700L,
                // 21:50 to 00:20 runs past midnight.
                9_000L,
                // Neither: the planner estimates.
                null,
            ),
            workouts.map { it.durationSeconds },
        )
    }

    @Test
    fun `every set keeps its reps and weight, failed sets their reps, and skipped sets are not history`() {
        val (first, second) = parsed("stronglifts-export.csv").workouts

        assertEquals(sets(5, 5, kg(60.0)), first.exercises[0].sets)
        assertEquals(sets(3, 5, kg(45.0)) + sets(1, 4, kg(45.0)) + sets(1, 3, kg(45.0)), first.exercises[1].sets)
        assertEquals(sets(4, 5, kg(50.0)), first.exercises[2].sets)
        // A set failed outright is written 0 and is not one, and the deadlift is one set.
        assertEquals(sets(4, 5, kg(32.5)), second.exercises[1].sets)
        assertEquals(sets(1, 5, kg(90.0)), second.exercises[2].sets)
        // The assistance is passed on as written, for the planner to drop.
        assertEquals(sets(3, 8, kg(-5.0)), second.exercises[3].sets)
    }

    @Test
    fun `a weighted dip keeps its load, and a timed exercise's sets are its seconds`() {
        val evening = parsed("stronglifts-export.csv").workouts[2]

        assertEquals(sets(3, 10, kg(10.0)), evening.exercises[1].sets)
        assertEquals(
            listOf(60L, 60L, 45L).map { ImportedSet(duration = it, weight = 0) },
            evening.exercises[3].sets,
        )
    }

    @Test
    fun `a note every row carries is the workout's, and notes that differ stay on their exercises`() {
        val workouts = parsed("stronglifts-export.csv").workouts

        assertNull(workouts[0].notes)
        assertTrue(workouts[0].exercises.all { it.notes == null })
        assertEquals("Felt strong, slept well", workouts[1].notes)
        assertTrue(workouts[1].exercises.all { it.notes == null })
        assertNull(workouts[2].notes)
        assertEquals(listOf("Belt on top set", null, null, null), workouts[2].exercises.map { it.notes })
        assertEquals("Gym was packed\nshort session", workouts[3].notes)
    }

    @Test
    fun `body weight is one measurement a day, taken when the day's first workout started`() {
        assertEquals(
            listOf(
                weighIn(kg(82.5), millis("2026-09-01T18:05:00Z")),
                weighIn(kg(82.0), millis("2026-09-03T07:30:00Z")),
                weighIn(kg(83.0), millis("2026-09-05T12:00:00Z")),
            ),
            parsed("stronglifts-export.csv").bodyMeasurements,
        )
    }

    @Test
    fun `a day's body weight is taken at its earliest workout, whatever order the rows come in`() {
        val history = parsedText(
            export(
                "2026/09/03,2,Workout B,81.5,Squat,,,,18:00,18:45,,5,60,,",
                "2026/09/03,1,Workout A,81,Squat,,,,07:30,08:15,,5,60,,",
            ),
        )

        assertEquals(2, history.workouts.size)
        assertEquals(listOf(weighIn(kg(81.0), millis("2026-09-03T07:30:00Z"))), history.bodyMeasurements)
    }

    @Test
    fun `exercises are mapped to the catalogue, the program's lifts with the bar their names leave out`() {
        val history = parsed("stronglifts-export.csv")
        val byName = history.exercises.associateBy { it.name }

        assertEquals(
            ImportedExercise(name = "Squat", catalogueName = "Barbell Squat", equipment = EquipmentType.BARBELL),
            byName["Squat"],
        )
        assertEquals(
            ImportedExercise(
                name = "Barbell Row",
                catalogueName = "Bent Over Barbell Row",
                equipment = EquipmentType.BARBELL,
            ),
            byName["Barbell Row"],
        )
        assertEquals(ImportedExercise(name = "Chinups", catalogueName = "Chin-Up"), byName["Chinups"])
        assertEquals(
            ImportedExercise(name = "Dumbbell Curl", equipment = EquipmentType.DUMBBELL),
            byName["Dumbbell Curl"],
        )
        assertEquals(ImportedExercise(name = "Planks"), byName["Planks"])
        // Every exercise named, once, in the order it first appears.
        assertEquals(
            listOf(
                "Squat", "Bench Press", "Barbell Row", "Overhead Press", "Deadlift", "Chinups", "Dips",
                "Dumbbell Curl", "Planks", "Front Squat", "Incline Bench Press",
            ),
            history.exercises.map { it.name },
        )
        assertTrue(history.exercises.all { it.type == null })
    }

    @Test
    fun `an export in pounds with every cell quoted and the summaries spelt with an x reads the same`() {
        val history = parsed("stronglifts-export-lb.csv")
        val (old, recent) = history.workouts

        assertEquals(millis("2015-05-25T19:37:00Z"), old.startedAt)
        assertEquals(3_600L, old.durationSeconds)
        assertEquals("Second week, adding weight next week", old.notes)
        assertEquals(listOf(sets(5, 5, lb45), sets(5, 5, lb45)), old.exercises.map { it.sets })

        assertEquals(millis("2026-04-30T07:00:00Z"), recent.startedAt)
        // 0.6 h is a minute off 07:00 to 07:35.
        assertEquals(2_100L, recent.durationSeconds)
        assertEquals(sets(1, 5, lb255), recent.exercises[0].sets)
        assertEquals(sets(3, 10, -4_535_924L), recent.exercises[1].sets)
        // The sixth set has columns of its own.
        assertEquals(sets(5, 3, 0L) + sets(1, 2, 0L), recent.exercises[2].sets)

        assertEquals(listOf(87_089_735L, 97_749_156L), history.bodyMeasurements.map { it.weight })
    }

    @Test
    fun `an export with semicolons and decimal commas reads the same`() {
        val history = parsed("stronglifts-export-semicolon.csv")
        val workout = history.workouts.single()

        assertEquals(millis("2026-10-12T18:30:00Z"), workout.startedAt)
        assertEquals(4_500L, workout.durationSeconds)
        assertEquals(sets(3, 5, kg(62.5)), workout.exercises[0].sets)
        assertEquals(
            sets(1, 5, kg(42.5)) + sets(1, 5, kg(35.0)) + sets(1, 4, kg(35.0)),
            workout.exercises[1].sets,
        )
        assertEquals(listOf(kg(80.5)), history.bodyMeasurements.map { it.weight })
    }

    @Test
    fun `a start time is read in the user's zone, in either clock, at whatever time of night`() {
        val paris = ImporterFixtures.context("Europe/Paris")
        val history = parsedText(
            export(
                "2026/10/24,1,Workout A,80,Squat,1×5,,,12:30 AM,1:00 AM,,5,60,,",
                "2026/10/23,2,Workout B,80,Squat,1×5,,,12:15 PM,1:00 PM,,5,60,,",
                "2026/10/22,3,Workout A,80,Squat,1×5,,,18:04,18:50,,5,60,,",
                "2026/10/21,4,Workout B,80,Squat,1×5,,,6:04 p.m.,,,5,60,,",
                "2026/10/20,5,Workout A,80,Squat,1×5,,,soon,,,5,60,,",
            ),
            paris,
        )

        assertEquals(
            listOf(
                // Summer time in Paris until the 25th: two hours ahead.
                millis("2026-10-23T22:30:00Z"),
                millis("2026-10-23T10:15:00Z"),
                millis("2026-10-22T16:04:00Z"),
                millis("2026-10-21T16:04:00Z"),
                // A start that does not read is noon.
                millis("2026-10-20T10:00:00Z"),
            ),
            history.workouts.map { it.startedAt },
        )
        assertEquals(listOf(1_800L, 2_700L, 2_760L, null, null), history.workouts.map { it.durationSeconds })
    }

    @Test
    fun `figures are in the units the headers name, and the user's where they name none`() {
        val pounds = "Date (yyyy/mm/dd),Body Weight (lbs),Exercise,Set 1 (Reps),Set 1 (LBS)\n2026/09/01,184,Squat,5,135"
        assertEquals(sets(1, 5, lb135), onlySets(pounds))
        assertEquals(listOf(83_460_996L), parsedText(pounds).bodyMeasurements.map { it.weight })

        val unlabelled = "Date (yyyy/mm/dd),Body Weight,Exercise,Set 1 (Reps),Set 1 (KG),Set 2 (Reps)\n" +
            "2026/09/01,184,Push Up,10,0,12"
        // A body weight without a unit is the user's, and so is a set with no weight column.
        assertEquals(listOf(83_460_996L), parsedText(unlabelled, IMPERIAL_UTC).bodyMeasurements.map { it.weight })
        assertEquals(listOf(184_000_000L), parsedText(unlabelled).bodyMeasurements.map { it.weight })
        assertEquals(listOf(ImportedSet(reps = 10, weight = 0), ImportedSet(reps = 12)), onlySets(unlabelled))
        // A duration in minutes, as a column could name it.
        assertEquals(
            listOf(5_400L),
            parsedText(
                "Date (yyyy/mm/dd),Exercise,Duration (min),Set 1 (Reps),Set 1 (KG)\n2026/09/01,Squat,90,5,60",
            ).workouts.map { it.durationSeconds },
        )
    }

    @Test
    fun `a column whose header names no unit Titan reads is malformed`() {
        listOf(
            "Date (yyyy/mm/dd),Exercise,Set 1 (Reps),Set 1 (stone)" to "Set 1 (stone)",
            "Date (yyyy/mm/dd),Body Weight (stone),Exercise,Set 1 (Reps),Set 1 (KG)" to "Body Weight (stone)",
            "Date (yyyy/mm/dd),Exercise,Duration (fortnights),Set 1 (Reps),Set 1 (KG)" to "Duration (fortnights)",
            "Date,Note,Workout,Body Weight,Exercise 1,Weight (stone),Set 1" to "Weight (stone)",
            "Date,Note,Workout,Body Weight (stone),Exercise 1,Weight (KG),Set 1" to "Body Weight (stone)",
        ).forEach { (columns, column) ->
            val result = read("$columns\n2026/09/01,1,1,1,1,1,1")
            assertTrue(result.toString(), result is ParseResult.Malformed && column in result.reason)
        }
    }

    // ------------------------------------------------------------------ one row per workout

    @Test
    fun `the older layout's rows are workouts at noon, read day first, two on one day one workout`() {
        val workouts = parsed("stronglifts-spreadsheet.csv").workouts

        assertEquals(
            listOf(millis("2017-05-02T12:00:00Z"), millis("2017-05-03T12:00:00Z"), millis("2017-05-15T12:00:00Z")),
            workouts.map { it.startedAt },
        )
        assertEquals(listOf("Workout A", "Workout B", "Workout A"), workouts.map { it.name })
        assertTrue(workouts.all { it.durationSeconds == null })
        // The assistance work past the header's three groups is read in groups of their shape.
        assertEquals(listOf("Squat", "Bench Press", "Barbell Row", "Dips", "Planks"), workouts[0].names())
        assertEquals(
            listOf("Squat", "Overhead Press", "Deadlift", "Chinups", "Hanging Knee Raises"),
            workouts[1].names(),
        )
        // The second row of the 15th joins the first; its squat had no sets.
        assertEquals(
            listOf("Squat", "Bench press", "Barbell row", "Dips", "Overhead press", "Deadlift"),
            workouts[2].names(),
        )
        assertEquals("Squats felt heavy, deload next week", workouts[2].notes)
        assertNull(workouts[0].notes)
    }

    @Test
    fun `an older row's weight is the exercise's, in the unit the body weight is written in`() {
        val (first, second, third) = parsed("stronglifts-spreadsheet.csv").workouts

        // Kilograms, as the body weight says, and not the pounds rounded to a plate beside them.
        assertEquals(sets(5, 5, kg(50.0)), first.exercises[0].sets)
        assertEquals(sets(3, 10, 0L), first.exercises[3].sets)
        assertEquals(sets(3, 30, 0L), first.exercises[4].sets)
        assertEquals(sets(5, 5, kg(52.5)), second.exercises[0].sets)
        assertEquals(sets(1, 5, kg(40.0)), second.exercises[2].sets)
        assertEquals(listOf(10, 5, 5), second.exercises[3].sets.map { it.reps })
        // Failed sets keep their reps, a 0 is no set, and a dip's added weight is kept.
        assertEquals(sets(3, 5, kg(66.0)) + sets(1, 4, kg(66.0)) + sets(1, 3, kg(66.0)), third.exercises[0].sets)
        assertEquals(sets(4, 5, kg(48.0)), third.exercises[2].sets)
        assertEquals(sets(3, 5, kg(8.5)), third.exercises[3].sets)
    }

    @Test
    fun `an older row's body weight is a measurement, and 0kg is a day without one`() {
        assertEquals(
            listOf(
                weighIn(kg(64.5), millis("2017-05-02T12:00:00Z")),
                weighIn(kg(64.25), millis("2017-05-03T12:00:00Z")),
                // The first row of the 15th says 0kg, the second 65kg.
                weighIn(kg(65.0), millis("2017-05-15T12:00:00Z")),
            ),
            parsed("stronglifts-spreadsheet.csv").bodyMeasurements,
        )
    }

    @Test
    fun `an older export read month first, in pounds, with an assistance group the header names`() {
        val history = parsed("stronglifts-spreadsheet-us.csv")
        val (first, second) = history.workouts

        assertEquals(millis("2015-02-13T12:00:00Z"), first.startedAt)
        assertEquals(millis("2015-02-16T12:00:00Z"), second.startedAt)
        assertEquals(listOf("Squat", "Bench press", "Barbell row", "Barbell Curl"), first.names())
        assertEquals(
            listOf(sets(5, 5, lb135), sets(5, 5, lb95), sets(5, 5, lb95), sets(3, 8, lb35)),
            first.exercises.map { it.sets },
        )
        assertEquals(listOf("Squat", "Overhead press", "Deadlift"), second.names())
        assertEquals(listOf(sets(5, 5, lb140), sets(5, 5, lb65), sets(1, 5, lb200)), second.exercises.map { it.sets })
        assertEquals("Gym closed early, skipped curls", second.notes)
        assertEquals(listOf(83_914_588L, 83_460_996L), history.bodyMeasurements.map { it.weight })
    }

    @Test
    fun `an older row without a body weight unit is read in the user's, and in the other column if that is empty`() {
        val row = "02/05/17,,A,,Squat,50,110,5,5,5"
        assertEquals(sets(3, 5, kg(50.0)), onlySets("$oldHeader\n$row\n13/05/17,,B,,Squat,1,1,,,"))
        assertEquals(sets(3, 5, 49_895_161L), onlySets("$oldHeader\n$row\n13/05/17,,B,,Squat,1,1,,,", IMPERIAL_UTC))
        assertEquals(
            sets(3, 5, kg(50.0)),
            onlySets("$oldHeader\n02/05/17,,A,,Squat,50,,5,5,5\n13/05/17,,B,,Squat,1,1,,,", IMPERIAL_UTC),
        )
    }

    @Test
    fun `dates whose order the file never settles, or settles both ways, are not guessed`() {
        listOf(
            "$oldHeader\n02/05/17,,A,64kg,Squat,50,110,5,5,5\n03/05/17,,B,64kg,Squat,52,115,5,5,5",
            "$oldHeader\n13/05/17,,A,64kg,Squat,50,110,5,5,5\n05/13/17,,B,64kg,Squat,52,115,5,5,5",
        ).forEach { text ->
            val result = read(text)
            assertTrue(result.toString(), result is ParseResult.Malformed && "2 rows" in result.reason)
        }
    }

    // ------------------------------------------------------------------ damaged files

    @Test
    fun `a bad row costs that row, and a bad figure costs its set, which the planner counts`() {
        val history = parsed("stronglifts-damaged.csv")
        val workout = history.workouts.single()

        assertEquals(millis("2026-10-01T18:00:00Z"), workout.startedAt)
        assertEquals(listOf("Squat", "Bench Press"), workout.names())
        assertEquals(
            listOf(
                ImportedSet(reps = 5, weight = kg(100.0)),
                ImportedSet(),
                ImportedSet(),
                ImportedSet(),
                ImportedSet(reps = 3, weight = kg(110.0)),
            ),
            workout.exercises[0].sets,
        )
        assertEquals(sets(1, 5, kg(70.0)), workout.exercises[1].sets)
        // A body weight that is not a number is an entry with nothing in it.
        assertEquals(
            listOf(BodyMeasurement(createdAt = workout.startedAt, updatedAt = workout.startedAt)),
            history.bodyMeasurements,
        )
    }

    @Test
    fun `what a damaged file loses is counted in the report, not dropped in silence`() {
        val plan = ImportPlanner.plan(parsed("stronglifts-damaged.csv"), ImporterFixtures.freshInstall(plannedNow))

        assertEquals(3, plan.skipped.invalidSets)
        assertEquals(1, plan.skipped.invalidMeasurements)
        assertEquals(3, plan.setCount)
    }

    @Test
    fun `an export whose every row has no date or exercise that reads is malformed`() {
        val result = read(
            export(
                "soon,1,Workout A,80,Squat,1×5,,,,,,5,60,,",
                "2026/09/01,1,Workout A,80,,1×5,,,,,,5,60,,",
            ),
        )

        assertTrue(result.toString(), result is ParseResult.Malformed && "2 rows" in result.reason)
    }

    @Test
    fun `CSV that breaks past the header is malformed, naming the line`() {
        val result = read(export("2026/09/01,1,Workout A,80,Squat,1×5,,,,,\"never closed,5,60,,"))

        assertTrue(result.toString(), result is ParseResult.Malformed && "line 2" in result.reason)
    }

    @Test
    fun `hostile text is carried as text and hostile figures are refused rather than believed`() {
        val history = parsedText(
            export(
                "2026/09/01,1,\"'; DROP TABLE session; --\",80,\"<script>\"\"x\"\"</script>\",,," +
                    "99999999999999999999,,,\"<b>note</b>\",NaN,60,5,1e5",
                "2026/09/01,1,Workout A,80,Bench Press‮\u0000,,,,,,," +
                    "99999999999999999999,99999999999999999999,,",
                "+999999999/12/31,1,Workout A,80,Squat,,,,,,,5,60,,",
            ),
        )

        val workout = history.workouts.single()
        val (injection, overflow) = workout.exercises
        assertEquals("'; DROP TABLE session; --", workout.name)
        assertEquals("<script>\"x\"</script>", injection.exercise.name)
        assertEquals("<b>note</b>", injection.notes)
        assertEquals(listOf(ImportedSet(), ImportedSet()), injection.sets)
        assertEquals("Bench Press‮\u0000", overflow.exercise.name)
        // Read as written and clamped; the planner refuses what Titan's bounds do not allow.
        assertEquals(Int.MAX_VALUE, overflow.sets.single().reps)
        assertEquals(Long.MAX_VALUE, overflow.sets.single().weight)
        assertEquals(Long.MAX_VALUE, workout.durationSeconds)
    }

    @Test
    fun `a long run of spaces in a header or a body weight is read or refused without holding up the file`() {
        val spaces = " ".repeat(60_000)
        val newer = parsedText(
            export(
                "2026/09/01,1,Workout A,80${spaces}kg,Squat,,,,,,,5,60,,",
                "2026/09/02,2,Workout B,80${spaces}1,Squat,,,,,,,5,60,,",
                columns = "$header,Comment${spaces}x,Set 3 ($spaces",
            ),
        )
        val older = parsedText("$oldHeader,Weight (${spaces}x\n15/05/17,,A,65kg,Squat,60,135,5,5,5")

        assertEquals(listOf(kg(80.0), null), newer.bodyMeasurements.map(BodyMeasurement::weight))
        assertEquals(sets(3, reps = 5, weight = kg(60.0)), older.workouts.single().exercises.single().sets)
    }

    // ------------------------------------------------------------------ through the planner

    @Test
    fun `a parsed export plans cleanly against a fresh install, onto the catalogue's own exercises`() {
        val plan = ImportPlanner.plan(parsed("stronglifts-export.csv"), ImporterFixtures.freshInstall(plannedNow))
        val catalogue = ImporterFixtures.catalogue.associateBy { it.name }
        fun existing(name: String) = ExerciseRef.Existing(catalogue.getValue(name).id)

        assertTrue(plan.skipped.toString(), plan.skipped.isEmpty)
        assertEquals(4, plan.workouts.size)
        assertEquals(47, plan.setCount)
        assertEquals(3, plan.measurements.size)
        val (first, second, third) = plan.workouts
        val fourth = plan.workouts[3]
        assertEquals(
            listOf("Barbell Squat", "Barbell Bench Press - Medium Grip", "Bent Over Barbell Row").map(::existing),
            first.exercises.map { it.exercise },
        )
        assertEquals(
            listOf("Barbell Squat", "Standing Military Press", "Barbell Deadlift", "Chin-Up").map(::existing),
            second.exercises.map { it.exercise },
        )
        // The assisted chin-ups keep their reps and drop the assistance.
        assertEquals(listOf(8, 8, 8), second.exercises[3].sets.map { it.reps })
        assertTrue(second.exercises[3].sets.all { it.weight == null })
        assertEquals(existing("Barbell Squat"), third.exercises[0].exercise)
        assertEquals(
            listOf("Front Barbell Squat", "Barbell Incline Bench Press - Medium Grip").map(::existing),
            fourth.exercises.map { it.exercise },
        )

        val created = plan.newExercises.associate {
            it.exercise.name to (it.exercise.type to it.exercise.equipmentType)
        }
        assertEquals(
            mapOf(
                "Dips" to (ExerciseType.STRENGTH to EquipmentType.OTHER),
                "Dumbbell Curl" to (ExerciseType.STRENGTH to EquipmentType.DUMBBELL),
                "Planks" to (ExerciseType.ISOMETRIC to EquipmentType.OTHER),
            ),
            created,
        )
    }

    @Test
    fun `importing the same file again is a duplicate of every workout and measurement`() {
        val history = parsed("stronglifts-export.csv")
        val first = ImportPlanner.plan(history, ImporterFixtures.freshInstall(plannedNow))

        val again = ImportPlanner.plan(
            history,
            ImporterFixtures.freshInstall(plannedNow).copy(
                sessionStarts = first.workouts.map { it.startedAt }.toSet(),
                measurementTimes = first.measurements.map { it.createdAt }.toSet(),
            ),
        )
        assertEquals(4, again.skipped.duplicateWorkouts)
        assertEquals(3, again.skipped.duplicateMeasurements)
        assertTrue(again.workouts.isEmpty())
    }

    @Test
    fun `the other exports plan cleanly too`() {
        mapOf(
            "stronglifts-export-lb.csv" to (2 to 20),
            "stronglifts-export-semicolon.csv" to (1 to 6),
            "stronglifts-spreadsheet.csv" to (3 to 60),
            "stronglifts-spreadsheet-us.csv" to (2 to 29),
        ).forEach { (sample, expected) ->
            val plan = ImportPlanner.plan(parsed(sample), ImporterFixtures.freshInstall(plannedNow))
            assertTrue("$sample: ${plan.skipped}", plan.skipped.isEmpty)
            assertEquals(sample, expected, plan.workouts.size to plan.setCount)
        }
    }
}
