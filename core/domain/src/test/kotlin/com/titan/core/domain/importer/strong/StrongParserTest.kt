package com.titan.core.domain.importer.strong

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
 * The Strong parser over sample exports in every layout Strong is known to have written, and
 * over the files it has to turn away or survive.
 */
class StrongParserTest {

    private val parser = StrongParser()

    private fun parsed(
        sample: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.resource("strong/$sample"), context)

    private fun parsedText(
        csv: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.text(csv), context)

    private fun read(csv: String): ParseResult = ImporterFixtures.read(parser, ImporterFixtures.text(csv))

    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()

    private fun ImportedWorkout.line(index: Int) = exercises[index]

    // ------------------------------------------------------------------ recognising the file

    @Test
    fun `anything that is not a Strong export is not recognised`() {
        listOf(
            ImporterFixtures.bytes(ByteArray(0)),
            ImporterFixtures.text("hello world\nthis is a shopping list\n"),
            ImporterFixtures.resource("hevy/hevy-workouts.csv"),
            ImporterFixtures.bytes(Random(seed = 247).nextBytes(4_096)),
            ImporterFixtures.text(
                "Date,Workout Name,Exercise Name,Weight,Reps\n2024-03-02 18:04:11,Push,Bench,100,5\n",
            ),
            ImporterFixtures.text("\"Date,Workout Name,Exercise Name,Set Order\n"),
        ).forEachIndexed { index, source ->
            assertEquals("source $index", ParseResult.NotRecognised, ImporterFixtures.read(parser, source))
        }
    }

    @Test
    fun `an export with no workouts in it is an empty history, not a broken file`() {
        assertEquals(ImportedHistory(), parsed("strong-header-only.csv"))
    }

    // ------------------------------------------------------------------ the iOS layout

    @Test
    fun `an iOS export becomes its workouts, in file order, each starting at its date`() {
        val workouts = parsed("strong-ios.csv").workouts

        assertEquals(listOf("Push Day", "Morning Run", "Legs"), workouts.map(ImportedWorkout::name))
        assertEquals(
            listOf(millis("2024-03-02T18:04:11Z"), millis("2024-03-04T07:30:00Z"), millis("2024-03-06T18:00:00Z")),
            workouts.map(ImportedWorkout::startedAt),
        )
        assertEquals(listOf(3_900L, 2_280L, 3_600L), workouts.map(ImportedWorkout::durationSeconds))
        assertEquals("Slept badly, still moved well", workouts[0].notes)
        assertNull(workouts[1].notes)
    }

    @Test
    fun `set order markers become set types, and a rest timer becomes the rest after the set before it`() {
        val bench = parsed("strong-ios.csv").workouts[0].line(0)

        assertEquals(
            listOf(
                ImportedSet(setType = SetType.WARMUP, reps = 8, weight = 60_000_000, duration = 0, distance = 0),
                ImportedSet(reps = 5, weight = 100_000_000, duration = 0, distance = 0, rpe = 80, restAfter = 180),
                ImportedSet(reps = 5, weight = 100_000_000, duration = 0, distance = 0, rpe = 85, restAfter = 180),
                ImportedSet(
                    setType = SetType.FAILURE,
                    reps = 3,
                    weight = 100_000_000,
                    duration = 0,
                    distance = 0,
                    rpe = 100,
                ),
                ImportedSet(setType = SetType.DROP_SET, reps = 8, weight = 75_000_000, duration = 0, distance = 0),
            ),
            bench.sets,
        )
    }

    @Test
    fun `an exercise's name maps onto the catalogue, with the equipment its parenthesis names`() {
        val bench = parsed("strong-ios.csv").workouts[0].line(0)

        assertEquals(
            ImportedExercise(
                name = "Bench Press (Barbell)",
                catalogueName = "Barbell Bench Press - Medium Grip",
                equipment = EquipmentType.BARBELL,
            ),
            bench.exercise,
        )
    }

    @Test
    fun `an exercise note, escaped or quoted over lines, is the exercise's note for the workout`() {
        val workouts = parsed("strong-ios.csv").workouts

        assertEquals("Pause the first rep\nElbows tucked", workouts[0].line(0).notes)
        assertEquals("Belt on\nheavy sets", workouts[2].line(0).notes)
        assertNull(workouts[2].line(1).notes)
    }

    @Test
    fun `an assisted exercise keeps its reps and drops the counterweight, a bodyweight one keeps its zero`() {
        val push = parsed("strong-ios.csv").workouts[0]

        assertEquals("Pull Up (Assisted)", push.line(1).exercise.name)
        assertEquals(ImportedSet(reps = 8, duration = 0, distance = 0), push.line(1).sets.single())
        assertEquals(ImportedSet(reps = 10, weight = 0, duration = 0, distance = 0), push.line(2).sets.single())
    }

    @Test
    fun `an assisted exercise's unreadable counterweight does not cost its reps`() {
        val csv = "Date,Workout Name,Exercise Name,Set Order,Weight,Reps\n2024-03-02 18:04:11,W,Pull Up (Assisted),1,heavy,8\n"

        assertEquals(ImportedSet(reps = 8), parsedText(csv).workouts.single().line(0).sets.single())
    }

    @Test
    fun `a distance in yards or feet is read in its unit, not the user's`() {
        val csv = """
            Date;Workout Name;Exercise Name;Set Order;Distance;Distance Unit;Seconds
            2024-03-02 18:04:11;W;Running;1;400;yd;90
            2024-03-02 18:04:11;W;Running;2;1000;ft;60
        """.trimIndent()

        assertEquals(
            listOf(36_576L, 30_480L),
            parsedText(csv).workouts.single().line(0).sets.map { it.distance },
        )
    }

    @Test
    fun `timed and distance sets keep their seconds and distance, and the zeros Strong writes beside them`() {
        val workouts = parsed("strong-ios.csv").workouts

        assertEquals(ImportedSet(reps = 0, weight = 0, duration = 60, distance = 0), workouts[0].line(3).sets.single())
        assertEquals(
            ImportedSet(reps = 0, weight = 0, duration = 1_860, distance = 520_000),
            workouts[1].line(0).sets.single(),
        )
    }

    @Test
    fun `an exercise performed again later in a workout is a second exercise, of the same source exercise`() {
        val legs = parsed("strong-ios.csv").workouts[2]

        assertEquals(
            listOf("Squat (Barbell)", "Romanian Deadlift (Barbell)", "Squat (Barbell)"),
            legs.exercises.map { it.exercise.name },
        )
        assertEquals(listOf(2, 1, 1), legs.exercises.map { it.sets.size })
        assertTrue(legs.line(0).exercise === legs.line(2).exercise)
    }

    @Test
    fun `a bare weight and distance are read in the unit the user chose`() {
        val workouts = parsed("strong-ios.csv", IMPERIAL_UTC).workouts

        assertEquals(45_359_237L, workouts[0].line(0).sets[1].weight)
        // 5.2 mi is 836,858.88 cm.
        assertEquals(836_859L, workouts[1].line(0).sets.single().distance)
    }

    @Test
    fun `a wall-clock date is read in the zone the user is in`() {
        val workouts = parsed("strong-ios.csv", ImporterFixtures.context("America/New_York")).workouts

        assertEquals(millis("2024-03-02T23:04:11Z"), workouts[0].startedAt)
    }

    // ------------------------------------------------------------------ the Android layout

    @Test
    fun `an Android export reads past its byte order mark, its semicolons and its quotes`() {
        val workouts = parsed("strong-android.csv").workouts

        assertEquals(2, workouts.size)
        val upper = workouts[0]
        assertEquals("Upper A", upper.name)
        assertEquals(millis("2026-03-02T18:04:11Z"), upper.startedAt)
        assertEquals(3_725L, upper.durationSeconds)
        assertEquals("Deload week", upper.notes)
        assertEquals(
            listOf("Bench Press (Dumbbell)", "Lat Pulldown (Cable)", "Rowing (Machine)"),
            upper.exercises.map { it.exercise.name },
        )
        assertNull(workouts[1].notes)
    }

    @Test
    fun `a note row is the exercise's note and no set, and blank cells are not recorded`() {
        val bench = parsed("strong-android.csv").workouts[0].line(0)

        assertEquals("Neutral grip", bench.notes)
        assertEquals(
            listOf(
                ImportedSet(setType = SetType.WARMUP, reps = 12, weight = 10_000_000),
                ImportedSet(reps = 8, weight = 30_000_000, rpe = 75, restAfter = 90),
                ImportedSet(reps = 8, weight = 30_000_000),
            ),
            bench.sets,
        )
    }

    @Test
    fun `units in the header win over the user's choice`() {
        val workouts = parsed("strong-android.csv", IMPERIAL_UTC).workouts

        assertEquals(55_000_000L, workouts[0].line(1).sets.single().weight)
        assertEquals(ImportedSet(duration = 480, distance = 200_000), workouts[0].line(2).sets.single())
        assertEquals(
            ImportedSet(reps = 3, weight = 180_000_000, rpe = 90, restAfter = 180),
            workouts[1].line(0).sets.single(),
        )
    }

    // ------------------------------------------------------------------ other layouts

    @Test
    fun `a unit column says each row's unit, and a blank one falls back to the user's`() {
        val arms = parsed("strong-unit-columns.csv", IMPERIAL_UTC).workouts.single()

        assertEquals(2_640L, arms.durationSeconds)
        assertEquals(listOf(14_000_000L, 13_607_771L), arms.line(0).sets.map(ImportedSet::weight))
        // 12,5 with no unit, from a decimal-comma locale: 12.5 lb.
        assertEquals(5_669_905L, arms.line(1).sets.single().weight)
        assertEquals(1_000_000L, arms.line(2).sets.single().distance)
        assertEquals(498_897L, arms.line(3).sets.single().distance)
    }

    @Test
    fun `an early export's unit-named columns are read in their unit`() {
        val workouts = parsed("strong-early.csv").workouts

        assertEquals(listOf(61_234_970L, 65_997_690L), workouts[0].line(0).sets.map(ImportedSet::weight))
        assertEquals(482_803L, workouts[1].line(0).sets.single().distance)
        assertNull(workouts[0].durationSeconds)
    }

    @Test
    fun `a pounds and miles header is read in pounds and miles, and a unit nobody knows in the user's`() {
        val header = "Date,Workout Name,Exercise Name,Set Order,Weight (lbs),Reps,Distance (miles),Seconds"
        val set = parsedText(
            "$header\n2024-03-02 18:04:11,W,Squat (Barbell),1,100,5,1,0\n",
        ).workouts.single().line(0).sets.single()
        assertEquals(45_359_237L, set.weight)
        assertEquals(160_934L, set.distance)

        val stones = parsedText(
            "Date,Workout Name,Exercise Name,Set Order,Weight (st),Reps\n2024-03-02 18:04:11,W,Squat,1,100,5\n",
        )
        assertEquals(100_000_000L, stones.workouts.single().line(0).sets.single().weight)
    }

    @Test
    fun `a weight column with no unit of its own is not read in a unit-named column's unit beside it`() {
        val csv = "Date,Workout Name,Exercise Name,Set Order,Weight,Reps,lbs\n2024-03-02 18:04:11,W,Squat,1,100,5,\n"

        assertEquals(100_000_000L, parsedText(csv).workouts.single().line(0).sets.single().weight)
    }

    @Test
    fun `workouts are told apart by their number as well as their date and name`() {
        val csv = """
            Workout #;Date;Workout Name;Exercise Name;Set Order;Weight (kg);Reps
            1;2024-03-02 18:04:11;Push;Bench Press (Barbell);1;100;5
            2;2024-03-02 18:04:11;Push;Bench Press (Barbell);1;100;5
            2;2024-03-02 18:04:11;Pull;Bench Press (Barbell);1;100;5
        """.trimIndent()

        assertEquals(3, parsedText(csv).workouts.size)
    }

    // ------------------------------------------------------------------ damaged files

    @Test
    fun `a bad row costs that row, and a bad number costs its set, which the planner then counts`() {
        val workouts = parsed("strong-damaged.csv").workouts

        // "yesterday evening" and the row with no date are lost; so is the row with no exercise.
        val good = workouts.single()
        assertEquals("Good", good.name)
        assertEquals(
            listOf(
                ImportedSet(reps = 5, weight = 100_000_000, duration = 0, distance = 0),
                // "heavy" is not a weight: nothing recorded, rather than a bodyweight set.
                ImportedSet(),
                // "hard" is not an RPE: the set stands without one.
                ImportedSet(reps = 5, weight = 100_000_000, duration = 0, distance = 0),
                // A row cut short after the exercise name.
                ImportedSet(),
                // A marker this parser does not know reads as a working set.
                ImportedSet(reps = 5, weight = 100_000_000, duration = 0, distance = 0),
            ),
            good.line(0).sets,
        )
    }

    @Test
    fun `an unreadable duration in a set costs the set`() {
        val csv = "Date,Workout Name,Exercise Name,Set Order,Weight,Reps,Seconds\n2024-03-02 18:04:11,W,Plank,1,0,0,a while\n"

        assertEquals(ImportedSet(), parsedText(csv).workouts.single().line(0).sets.single())
    }

    @Test
    fun `a rest timer with no set before it, or no length, attaches to nothing`() {
        val csv = """
            Date,Workout Name,Exercise Name,Set Order,Weight,Reps,Seconds
            2024-03-02 18:04:11,W,Squat (Barbell),Rest Timer,0,0,120
            2024-03-02 18:04:11,W,Squat (Barbell),1,100,5,0
            2024-03-02 18:04:11,W,Squat (Barbell),Rest Timer,0,0,
        """.trimIndent()

        assertEquals(
            listOf(ImportedSet(reps = 5, weight = 100_000_000, duration = 0)),
            parsedText(csv).workouts.single().line(0).sets,
        )
    }

    @Test
    fun `a file whose every row is unreadable is malformed`() {
        val result = read("Date,Workout Name,Exercise Name,Set Order\nlast week,Push,Bench,1\n,Push,Bench,2\n")

        assertTrue(result.toString(), result is ParseResult.Malformed)
    }

    @Test
    fun `CSV that breaks past the header is malformed, naming the line`() {
        val result = ImporterFixtures.read(parser, ImporterFixtures.resource("strong/strong-unclosed-quote.csv"))

        assertTrue(result.toString(), result is ParseResult.Malformed && "line 3" in result.reason)
    }

    // ------------------------------------------------------------------ through the planner

    @Test
    fun `a parsed export plans cleanly against a fresh install, onto the catalogue's own exercises`() {
        val history = parsed("strong-ios.csv")
        val plan = ImportPlanner.plan(
            history,
            ImporterFixtures.freshInstall(nowMillis = millis("2026-11-16T00:00:00Z")),
        )
        val catalogue = ImporterFixtures.catalogue.associateBy { it.name }

        assertTrue(plan.skipped.toString(), plan.skipped.isEmpty)
        assertEquals(3, plan.workouts.size)
        val push = plan.workouts[0]
        assertEquals(
            ExerciseRef.Existing(catalogue.getValue("Barbell Bench Press - Medium Grip").id),
            push.exercises[0].exercise,
        )
        assertEquals(ExerciseRef.Existing(catalogue.getValue("Pullups").id), push.exercises[2].exercise)
        assertEquals(listOf(null, 180L, 180L, null, null), push.exercises[0].sets.map { it.restAfter })
        assertEquals(
            listOf(SetType.WARMUP, SetType.NORMAL, SetType.NORMAL, SetType.FAILURE, SetType.DROP_SET),
            push.exercises[0].sets.map { it.setType },
        )
        assertEquals(push.startedAt + 3_900_000, push.finishedAt)

        val legs = plan.workouts[2]
        assertEquals(ExerciseRef.Existing(catalogue.getValue("Barbell Squat").id), legs.exercises[0].exercise)
        assertEquals(ExerciseRef.Existing(catalogue.getValue("Romanian Deadlift").id), legs.exercises[1].exercise)

        // The assisted pull-up, the plank (timed, where the catalogue's is rep-counted) and the
        // run (no plain run in the catalogue) are the user's own, created once each.
        val created = plan.newExercises.map { it.exercise }
        assertEquals(listOf("Pull Up (Assisted)", "Plank", "Running"), created.map { it.name })
        assertEquals(
            listOf(ExerciseType.STRENGTH, ExerciseType.ISOMETRIC, ExerciseType.ENDURANCE),
            created.map { it.type },
        )
    }

    @Test
    fun `an Android export plans cleanly too, its rowing landing on the catalogue's rower`() {
        val plan = ImportPlanner.plan(
            parsed("strong-android.csv"),
            ImporterFixtures.freshInstall(nowMillis = millis("2026-11-16T00:00:00Z")),
        )
        val catalogue = ImporterFixtures.catalogue.associateBy { it.name }

        assertTrue(plan.skipped.toString(), plan.skipped.isEmpty)
        assertEquals(
            listOf(
                "Dumbbell Bench Press",
                "Wide-Grip Lat Pulldown",
                "Rowing, Stationary",
            ).map { ExerciseRef.Existing(catalogue.getValue(it).id) },
            plan.workouts[0].exercises.map { it.exercise },
        )
        assertTrue(plan.newExercises.isEmpty())
    }

    @Test
    fun `the sets a damaged file loses are counted in the report, not dropped in silence`() {
        val plan = ImportPlanner.plan(
            parsed("strong-damaged.csv"),
            ImporterFixtures.freshInstall(nowMillis = millis("2026-11-16T00:00:00Z")),
        )

        assertEquals(2, plan.skipped.invalidSets)
        assertEquals(3, plan.setCount)
    }
}
