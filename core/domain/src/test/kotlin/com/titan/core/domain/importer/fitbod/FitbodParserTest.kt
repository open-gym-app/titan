package com.titan.core.domain.importer.fitbod

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
 * The Fitbod parser over sample exports from the app, a spreadsheet's re-save of one and the
 * extract Fitbod's support sends Android users, and over the files it has to turn away or survive.
 */
class FitbodParserTest {

    private val parser = FitbodParser()

    private fun parsed(
        sample: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.resource("fitbod/$sample"), context)

    private fun parsedText(
        text: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.text(text), context)

    private fun read(text: String): ParseResult = ImporterFixtures.read(parser, ImporterFixtures.text(text))

    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()

    private fun ImportedWorkout.names() = exercises.map { it.exercise.name }

    private val plannedNow = millis("2026-11-16T00:00:00Z")

    private val header = "Date,Exercise,Reps,Weight(kg),Duration(s),Distance(m),Incline,Resistance,isWarmup,Note,multiplier"

    /** An app export of [rows] under [header]. */
    private fun export(vararg rows: String): String = (listOf(header) + rows).joinToString("\n")

    /** The sets of the only exercise of the only workout [text] holds. */
    private fun onlySets(
        text: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): List<ImportedSet> = parsedText(text, context).workouts.single().exercises.single().sets

    // Fitbod's conversions of the pound figures the samples were logged in, as Titan stores them.
    private val lb17p5 = 7_937_866L
    private val lb25 = 11_339_809L
    private val lb45 = 20_411_657L
    private val lb90 = 40_823_313L
    private val lb95 = 43_091_275L
    private val lb135 = 61_234_970L
    private val lb185 = 83_914_588L
    private val lb225 = 102_058_283L

    // ------------------------------------------------------------------ recognising the file

    @Test
    fun `anything that is not a Fitbod export is not recognised`() {
        listOf(
            ImporterFixtures.bytes(ByteArray(0)),
            ImporterFixtures.text("hello world\nthis is a shopping list\n"),
            ImporterFixtures.bytes(Random(seed = 251).nextBytes(4_096)),
            // Fitbod's columns without the warm-up flag no other app writes, or without a weight.
            ImporterFixtures.text("Date,Exercise,Reps,Weight(kg)\n2026-10-24 18:24:59 +0000,Push Up,10,0\n"),
            ImporterFixtures.text("Date,Exercise,Reps,isWarmup\n2026-10-24 18:24:59 +0000,Push Up,10,false\n"),
            ImporterFixtures.text("date,Reps,weight_kg,isWarmup\n2026-10-24,10,0,false\n"),
            // A first record that is not CSV at all.
            ImporterFixtures.text("\"$header\n"),
        ).forEachIndexed { index, source ->
            assertEquals("source $index", ParseResult.NotRecognised, ImporterFixtures.read(parser, source))
        }
    }

    @Test
    fun `no Strong, Hevy, FitNotes or JEFIT export is a Fitbod export`() {
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
        ).forEach { sample ->
            assertEquals(
                sample,
                ParseResult.NotRecognised,
                ImporterFixtures.read(parser, ImporterFixtures.resource(sample)),
            )
        }
    }

    @Test
    fun `no StrongLifts export is a Fitbod export`() {
        listOf(
            "stronglifts-export.csv",
            "stronglifts-export-lb.csv",
            "stronglifts-export-semicolon.csv",
            "stronglifts-spreadsheet.csv",
            "stronglifts-spreadsheet-us.csv",
            "stronglifts-damaged.csv",
        ).forEach { sample ->
            assertEquals(
                sample,
                ParseResult.NotRecognised,
                ImporterFixtures.read(parser, ImporterFixtures.resource("stronglifts/$sample")),
            )
        }
    }

    @Test
    fun `an export with no sets in it is an empty history, not a broken file`() {
        assertEquals(ImportedHistory(), parsedText(header))
        assertEquals(ImportedHistory(), parsedText("$header\n\n"))
    }

    // ------------------------------------------------------------------ workouts

    @Test
    fun `the rows sharing an instant are one workout, two the same day are two, in the file's order`() {
        val workouts = parsed("fitbod-export.csv").workouts

        assertEquals(
            listOf(
                millis("2026-10-24T18:24:59Z"),
                millis("2026-10-24T10:05:12Z"),
                millis("2026-10-20T22:16:08Z"),
                millis("2018-09-15T13:19:44Z"),
            ),
            workouts.map { it.startedAt },
        )
        // Fitbod exports no name, no end and no workout note: the planner estimates the length.
        workouts.forEach { workout ->
            assertNull(workout.name)
            assertNull(workout.durationSeconds)
            assertNull(workout.notes)
        }
        assertEquals(
            listOf(
                "Rowing",
                "Barbell Bench Press",
                "Dumbbell Incline Bench Press",
                "Assisted Chin Up",
                "Cable Face Pull",
                "Cable Shoulder Internal Rotation at 90°",
            ),
            workouts[0].names(),
        )
        assertEquals(listOf("Walking", "Plank"), workouts[1].names())
    }

    @Test
    fun `a circuit that comes back to an exercise is two exercises of the workout, in the order they happened`() {
        val squats = parsed("fitbod-export.csv").workouts[2]

        assertEquals(
            listOf("Back Squat", "Dumbbell Lunge", "Push Up", "Air Squats", "Push Up", "Dumbbell Romanian Deadlift"),
            squats.names(),
        )
        assertEquals(squats.exercises[2].exercise, squats.exercises[4].exercise)
        assertEquals(listOf(15), squats.exercises[2].sets.map { it.reps })
        assertEquals(listOf(12), squats.exercises[4].sets.map { it.reps })
    }

    @Test
    fun `a workout is its instant, wherever its rows are and whatever offset writes it`() {
        val history = parsedText(
            export(
                "2026-10-24 18:24:59 +0000,Push Up, 10,0.0,0.0,0.0,0.0,0.0,false,, 1.0",
                "2026-10-20 18:00:00 +0000,Push Up, 8,0.0,0.0,0.0,0.0,0.0,false,, 1.0",
                "2026-10-24 20:24:59 +0200,Push Up, 9,0.0,0.0,0.0,0.0,0.0,false,, 1.0",
            ),
        )

        assertEquals(
            listOf(millis("2026-10-24T18:24:59Z"), millis("2026-10-20T18:00:00Z")),
            history.workouts.map { it.startedAt },
        )
        assertEquals(listOf(10, 9), history.workouts[0].exercises.single().sets.map { it.reps })
    }

    @Test
    fun `a time keeps its own offset in any zone, one without is the user's, and a bare day starts at noon`() {
        val paris = ImporterFixtures.context("Europe/Paris")
        val history = parsedText(
            export(
                "2026-10-24 18:24:59 +0000,Push Up, 1,0.0,0.0,0.0,0.0,0.0,false,, 1.0",
                "2026-10-23 18:24:59 -0500,Push Up, 1,0.0,0.0,0.0,0.0,0.0,false,, 1.0",
                "2026-10-22T18:24:59+05:30,Push Up, 1,0.0,0.0,0.0,0.0,0.0,false,, 1.0",
                "2026-10-21 18:24:59,Push Up, 1,0.0,0.0,0.0,0.0,0.0,false,, 1.0",
                "2026-10-20,Push Up, 1,0.0,0.0,0.0,0.0,0.0,false,, 1.0",
            ),
            paris,
        )

        assertEquals(
            listOf(
                millis("2026-10-24T18:24:59Z"),
                millis("2026-10-23T23:24:59Z"),
                millis("2026-10-22T12:54:59Z"),
                // Summer time in Paris until the 25th: two hours ahead.
                millis("2026-10-21T16:24:59Z"),
                millis("2026-10-20T10:00:00Z"),
            ),
            history.workouts.map { it.startedAt },
        )
    }

    // ------------------------------------------------------------------ sets

    @Test
    fun `every set keeps its weight and reps in Titan's units, warm-ups marked, notes on the exercise`() {
        val upper = parsed("fitbod-export.csv").workouts[0]
        val bench = upper.exercises[1]

        assertEquals(
            listOf(
                ImportedSet(setType = SetType.WARMUP, reps = 10, weight = lb45, duration = 0, distance = 0),
                ImportedSet(reps = 8, weight = lb135, duration = 0, distance = 0),
                ImportedSet(reps = 8, weight = lb135, duration = 0, distance = 0),
                ImportedSet(reps = 6, weight = lb135, duration = 0, distance = 0),
            ),
            bench.sets,
        )
        // The same note on two sets is kept once, and each distinct one in order.
        assertEquals("felt heavy, stop at 8\nLast set grindy", bench.notes)
        // A figure logged in kilograms stays in kilograms, beside the pound figures.
        assertEquals(listOf(22_500_000L), upper.exercises[4].sets.map { it.weight })
        assertEquals(listOf(4_000_000L), upper.exercises[5].sets.map { it.weight })
        // A note spread over lines in quotes.
        assertEquals("Left knee\nfelt fine", parsed("fitbod-export.csv").workouts[2].exercises[1].notes)
        assertNull(upper.exercises[2].notes)
    }

    @Test
    fun `a dumbbell's weight is what one hand held, whatever the multiplier counts it as`() {
        val workouts = parsed("fitbod-export.csv").workouts

        // multiplier 2: a pair of 17.5 lb dumbbells.
        assertEquals(listOf(lb17p5, lb17p5), workouts[0].exercises[2].sets.map { it.weight })
        // multiplier 4: a 25 lb dumbbell in each hand, on each leg.
        assertEquals(listOf(lb25, lb25), workouts[2].exercises[1].sets.map { it.weight })
        // multiplier 1: the bar.
        assertEquals(listOf(lb135, lb185, lb185), workouts[2].exercises[0].sets.map { it.weight })
    }

    @Test
    fun `an assisted exercise keeps its reps and drops the assistance`() {
        val chinUps = parsed("fitbod-export.csv").workouts[0].exercises[3]

        assertEquals("Assisted Chin Up", chinUps.exercise.name)
        assertEquals(
            listOf(
                ImportedSet(reps = 7, duration = 0, distance = 0),
                ImportedSet(reps = 6, duration = 0, distance = 0),
            ),
            chinUps.sets,
        )
        // The assistance is never recorded, so assistance that does not read costs nothing.
        assertEquals(
            listOf(ImportedSet(reps = 7, duration = 0, distance = 0)),
            onlySets(export("2026-10-01 18:00:00 +0000,Assisted Chin Up, 7,lots,0.0,0.0,0.0,0.0,false,, 0.0")),
        )
    }

    @Test
    fun `cardio keeps its time and distance, and holds their time`() {
        val workouts = parsed("fitbod-export.csv").workouts

        assertEquals(
            listOf(ImportedSet(setType = SetType.WARMUP, reps = 0, weight = 0, duration = 300, distance = 100_000)),
            workouts[0].exercises[0].sets,
        )
        assertEquals(
            listOf(ImportedSet(reps = 0, weight = 0, duration = 1_655, distance = 155_938)),
            workouts[1].exercises[0].sets,
        )
        assertEquals(listOf(45L, 40L), workouts[1].exercises[1].sets.map { it.duration })
        // The treadmill's incline is not a figure Titan keeps.
        assertEquals(
            listOf(ImportedSet(reps = 0, weight = 0, duration = 1_380, distance = 321_869)),
            workouts[3].exercises[0].sets,
        )
    }

    @Test
    fun `an older export's hold in reps is a hold of that many seconds, and a counted exercise keeps its reps`() {
        val old = parsed("fitbod-export.csv").workouts[3]

        assertEquals(
            listOf(
                ImportedSet(weight = 0, duration = 60, distance = 0),
                ImportedSet(weight = 0, duration = 45, distance = 0),
            ),
            old.exercises[1].sets,
        )
        assertEquals(listOf(30L), old.exercises[2].sets.map { it.duration })
        // A multiplier of 1 is an exercise Fitbod counts by reps.
        assertEquals(listOf(ImportedSet(reps = 20, weight = 0, duration = 0, distance = 0)), old.exercises[3].sets)
        // A recorded time or distance wins: the reps are not read as seconds then.
        assertEquals(
            listOf(ImportedSet(reps = 5, weight = 0, duration = 40, distance = 0)),
            onlySets(export("2018-09-15 13:19:44 +0000,Plank, 5,0.0,40.0,0.0,0.0,0.0,false,, 0.0")),
        )
    }

    @Test
    fun `each figure is the pound figure it was logged as, or the kilograms it was written in`() {
        mapOf(
            // Kilogram figures, to the hundredth, and beyond it.
            20.0 to 20_000_000L,
            24.0 to 24_000_000L,
            1.125 to 1_125_000L,
            0.0 to 0L,
            // Fitbod's conversions of 1, 17.5 and 45 lb, as the app and a spreadsheet write them.
            0.45359290943563974 to 453_592L,
            7.937875915123695 to lb17p5,
            20.41168092460379 to lb45,
            43.0913264 to lb95,
            102.0584046 to lb225,
            // Half a tonne is a whole number of hundredths of both, and stays kilograms.
            500.0 to 500_000_000L,
            // The true pound's conversion is exact in milligrams already.
            31.7514659 to 31_751_466L,
        ).forEach { (kilograms, milligrams) ->
            assertEquals("$kilograms", milligrams, FitbodParser.milligrams(kilograms))
        }
    }

    @Test
    fun `exercises are known by name, mapped to the catalogue with the equipment their name starts with`() {
        val workouts = parsed("fitbod-export.csv").workouts
        val byName = workouts.flatMap { it.exercises }.associate { it.exercise.name to it.exercise }

        assertEquals(
            ImportedExercise(
                name = "Barbell Bench Press",
                catalogueName = "Barbell Bench Press - Medium Grip",
                equipment = EquipmentType.BARBELL,
            ),
            byName["Barbell Bench Press"],
        )
        assertEquals(ImportedExercise(name = "Back Squat", catalogueName = "Barbell Squat"), byName["Back Squat"])
        assertEquals(EquipmentType.CABLE, byName.getValue("Cable Shoulder Internal Rotation at 90°").equipment)
        assertEquals(ImportedExercise(name = "Walking"), byName["Walking"])
        // The planner infers the type from the sets.
        assertTrue(byName.values.all { it.type == null })
    }

    // ------------------------------------------------------------------ variants

    @Test
    fun `a spreadsheet's re-save reads the same, with its pounds cut short`() {
        val (deadlifts, legs) = parsed("fitbod-export-resaved.csv").workouts

        assertEquals(millis("2026-09-05T11:01:45Z"), deadlifts.startedAt)
        assertEquals(listOf("Rowing", "Deadlift", "Dumbbell Row"), deadlifts.names())
        assertEquals(
            listOf(ImportedSet(reps = 0, weight = 0, duration = 157, distance = 44_800)),
            deadlifts.exercises[0].sets,
        )
        assertEquals(
            listOf(
                ImportedSet(setType = SetType.WARMUP, reps = 10, weight = lb95, duration = 0, distance = 0),
                ImportedSet(setType = SetType.WARMUP, reps = 5, weight = lb135, duration = 0, distance = 0),
                ImportedSet(reps = 5, weight = lb225, duration = 0, distance = 0),
                ImportedSet(reps = 5, weight = lb225, duration = 0, distance = 0),
            ),
            deadlifts.exercises[1].sets,
        )
        assertEquals(listOf(24_000_000L, 24_000_000L), deadlifts.exercises[2].sets.map { it.weight })
        assertEquals(millis("2026-09-02T11:16:04Z"), legs.startedAt)
        assertEquals(listOf(lb90, lb90), legs.exercises.single().sets.map { it.weight })
    }

    @Test
    fun `the support extract groups a day's sets into its workout, at noon, from its empty cells`() {
        val (twelfth, tenth) = parsed("fitbod-support-export.csv").workouts

        assertEquals(millis("2026-08-12T12:00:00Z"), twelfth.startedAt)
        assertEquals(listOf("Back Squat", "Seated Leg Curl", "Elliptical"), twelfth.names())
        assertEquals(
            listOf(
                ImportedSet(setType = SetType.WARMUP, reps = 5, weight = 60_000_000),
                ImportedSet(reps = 5, weight = 100_000_000),
            ),
            twelfth.exercises[0].sets,
        )
        // 70 lb converted with the true pound, which is already exact in milligrams.
        assertEquals(listOf(31_751_466L), twelfth.exercises[1].sets.map { it.weight })
        assertEquals(listOf(ImportedSet(duration = 600, distance = 80_467)), twelfth.exercises[2].sets)

        assertEquals(millis("2026-08-10T12:00:00Z"), tenth.startedAt)
        assertEquals(listOf("Farmer's Walk", "Back Squat"), tenth.names())
        assertEquals(listOf(ImportedSet(weight = lb45, distance = 1_829)), tenth.exercises[0].sets)
    }

    @Test
    fun `figures are in the units the header names, and the user's where it names none`() {
        val pounds = onlySets(
            "Date,Exercise,Reps,Weight(lbs),Duration(s),Distance(km),isWarmup\n" +
                "2026-10-24 18:24:59 +0000,Sled Push,10,17.5,30,0.05,false",
        )
        assertEquals(listOf(ImportedSet(reps = 10, weight = lb17p5, duration = 30, distance = 5_000)), pounds)

        val unlabelled = "Date,Exercise,Reps,Weight,Distance,isWarmup\n2026-10-24 18:24:59 +0000,Sled Push,10,17.5,1,false"
        assertEquals(
            listOf(ImportedSet(reps = 10, weight = lb17p5, distance = 160_934)),
            onlySets(unlabelled, IMPERIAL_UTC),
        )
        assertEquals(
            listOf(ImportedSet(reps = 10, weight = 17_500_000, distance = 100_000)),
            onlySets(unlabelled),
        )
        // Kilograms in the user's setting are still read for the pounds they convert.
        assertEquals(
            listOf(lb17p5),
            onlySets("Date,Exercise,Reps,Weight,isWarmup\n2026-10-24 18:24:59 +0000,Curl,10,7.937875915123695,false")
                .map { it.weight },
        )
    }

    @Test
    fun `a column whose header names no unit Titan reads is malformed`() {
        listOf(
            "Date,Exercise,Reps,Weight(stone),isWarmup" to "Weight(stone)",
            "Date,Exercise,Reps,Weight(kg),Distance(parsecs),isWarmup" to "Distance(parsecs)",
            "Date,Exercise,Reps,Weight(kg),Duration(min),isWarmup" to "Duration(min)",
        ).forEach { (columns, column) ->
            val result = read("$columns\n2026-10-24 18:24:59 +0000,Curl,10,1,1,false")
            assertTrue(result.toString(), result is ParseResult.Malformed && column in result.reason)
        }
    }

    // ------------------------------------------------------------------ damaged files

    @Test
    fun `a bad row costs that row, and a bad figure costs its set, which the planner counts`() {
        val workout = parsed("fitbod-damaged.csv").workouts.single()
        val (squat, walk) = workout.exercises

        assertEquals(millis("2026-10-01T18:00:00Z"), workout.startedAt)
        assertEquals(
            listOf(
                ImportedSet(reps = 5, weight = 100_000_000, duration = 0, distance = 0),
                ImportedSet(),
                ImportedSet(),
                // An unreadable warm-up flag or multiplier costs only what it would have said.
                ImportedSet(reps = 3, weight = 110_000_000, duration = 0, distance = 0),
            ),
            squat.sets,
        )
        assertEquals(
            listOf(
                ImportedSet(),
                ImportedSet(),
                ImportedSet(),
                ImportedSet(reps = 0, weight = 0, duration = 900, distance = 120_000),
            ),
            walk.sets,
        )
    }

    @Test
    fun `the sets a damaged file loses are counted in the report, not dropped in silence`() {
        val plan = ImportPlanner.plan(parsed("fitbod-damaged.csv"), ImporterFixtures.freshInstall(plannedNow))

        assertEquals(5, plan.skipped.invalidSets)
        assertEquals(3, plan.setCount)
    }

    @Test
    fun `an export whose every row has no date or exercise that reads is malformed`() {
        val result = read(
            export(
                "soon,Push Up, 10,0.0,0.0,0.0,0.0,0.0,false,, 1.0",
                "2026-10-24 18:24:59 +0000,, 10,0.0,0.0,0.0,0.0,0.0,false,, 1.0",
            ),
        )

        assertTrue(result.toString(), result is ParseResult.Malformed && "2 rows" in result.reason)
    }

    @Test
    fun `CSV that breaks past the header is malformed, naming the line`() {
        val result = read(
            export("2026-10-24 18:24:59 +0000,Push Up, 10,0.0,0.0,0.0,0.0,0.0,false,\"never closed, 1.0"),
        )

        assertTrue(result.toString(), result is ParseResult.Malformed && "line 2" in result.reason)
    }

    @Test
    fun `hostile text is carried as text and hostile figures are refused rather than believed`() {
        val history = parsedText(
            export(
                "2026-10-24 18:24:59 +0000,\"'; DROP TABLE session; --\", 99999999999999999999,1e5,NaN," +
                    "0.0,0.0,0.0,false,\"<script>\"\"x\"\"</script>\", 1.0",
                "2026-10-24 18:24:59 +0000,Bench Press‮\u0000, 99999999999999999999,99999999999999999999,0.0,0.0,0.0,0.0,false,, 1.0",
                "+999999999-12-31 23:59:59 +0000,Push Up, 10,0.0,0.0,0.0,0.0,0.0,false,, 1.0",
            ),
        )

        val (injection, overflow) = history.workouts.single().exercises
        assertEquals("'; DROP TABLE session; --", injection.exercise.name)
        assertEquals("<script>\"x\"</script>", injection.notes)
        assertEquals(listOf(ImportedSet()), injection.sets)
        assertEquals("Bench Press‮\u0000", overflow.exercise.name)
        // Read as written and clamped; the planner refuses what Titan's bounds do not allow.
        assertEquals(Int.MAX_VALUE, overflow.sets.single().reps)
        assertEquals(Long.MAX_VALUE, overflow.sets.single().weight)
    }

    // ------------------------------------------------------------------ through the planner

    @Test
    fun `a parsed export plans cleanly against a fresh install, onto the catalogue's own exercises`() {
        val plan = ImportPlanner.plan(parsed("fitbod-export.csv"), ImporterFixtures.freshInstall(plannedNow))
        val catalogue = ImporterFixtures.catalogue.associateBy { it.name }
        fun existing(name: String) = ExerciseRef.Existing(catalogue.getValue(name).id)

        assertTrue(plan.skipped.toString(), plan.skipped.isEmpty)
        assertEquals(4, plan.workouts.size)
        assertEquals(28, plan.setCount)
        // The planner orders workouts by their start, oldest first.
        val (old, lower, morning) = plan.workouts
        val upper = plan.workouts[3]
        assertEquals(
            listOf(existing("Barbell Bench Press - Medium Grip"), existing("Incline Dumbbell Press")),
            upper.exercises.subList(1, 3).map { it.exercise },
        )
        assertEquals(existing("Face Pull"), upper.exercises[4].exercise)
        assertEquals(
            listOf("Barbell Squat", "Dumbbell Lunges", "Pushups", "Bodyweight Squat", "Pushups").map(::existing),
            lower.exercises.take(5).map { it.exercise },
        )
        assertEquals(existing("Running, Treadmill"), old.exercises[0].exercise)
        // Every plank is timed, so the two workouts' planks are one exercise.
        assertEquals(morning.exercises[1].exercise, old.exercises[1].exercise)

        val created = plan.newExercises.associate {
            it.exercise.name to (it.exercise.type to it.exercise.equipmentType)
        }
        assertEquals(
            mapOf(
                "Rowing" to (ExerciseType.ENDURANCE to EquipmentType.OTHER),
                "Assisted Chin Up" to (ExerciseType.STRENGTH to EquipmentType.OTHER),
                "Cable Shoulder Internal Rotation at 90°" to (ExerciseType.STRENGTH to EquipmentType.CABLE),
                "Walking" to (ExerciseType.ENDURANCE to EquipmentType.OTHER),
                "Plank" to (ExerciseType.ISOMETRIC to EquipmentType.OTHER),
                "Dumbbell Romanian Deadlift" to (ExerciseType.STRENGTH to EquipmentType.DUMBBELL),
                "Side Bridge" to (ExerciseType.ISOMETRIC to EquipmentType.OTHER),
                // The catalogue's Superman is a hold, and Fitbod's counts reps.
                "Superman" to (ExerciseType.STRENGTH to EquipmentType.OTHER),
            ),
            created,
        )
    }

    @Test
    fun `importing the same file again is a duplicate of every workout`() {
        val history = parsed("fitbod-export.csv")
        val first = ImportPlanner.plan(history, ImporterFixtures.freshInstall(plannedNow))

        val again = ImportPlanner.plan(
            history,
            ImporterFixtures.freshInstall(plannedNow).copy(sessionStarts = first.workouts.map { it.startedAt }.toSet()),
        )
        assertEquals(4, again.skipped.duplicateWorkouts)
        assertTrue(again.workouts.isEmpty())
    }

    @Test
    fun `a re-saved export and the support extract plan too, the carry with no reps counted`() {
        val resaved = ImportPlanner.plan(parsed("fitbod-export-resaved.csv"), ImporterFixtures.freshInstall(plannedNow))
        assertTrue(resaved.skipped.toString(), resaved.skipped.isEmpty)
        assertEquals(2, resaved.workouts.size)
        assertEquals(9, resaved.setCount)

        val support = ImportPlanner.plan(parsed("fitbod-support-export.csv"), ImporterFixtures.freshInstall(plannedNow))
        assertEquals(2, support.workouts.size)
        assertEquals(5, support.setCount)
        // A loaded carry by distance alone is no shape of set Titan logs.
        assertEquals(1, support.skipped.invalidSets)
    }
}
