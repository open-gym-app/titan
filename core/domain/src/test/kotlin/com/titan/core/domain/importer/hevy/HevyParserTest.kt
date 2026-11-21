package com.titan.core.domain.importer.hevy

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * The Hevy parser over sample exports from the app and the website, in kilograms and pounds, in
 * English and in German, and over the files it has to turn away or survive.
 */
class HevyParserTest {

    private val parser = HevyParser()

    private fun parsed(
        sample: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.resource("hevy/$sample"), context)

    private fun parsedText(
        csv: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.text(csv), context)

    private fun read(csv: String): ParseResult = ImporterFixtures.read(parser, ImporterFixtures.text(csv))

    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()

    private fun ImportedWorkout.line(index: Int) = exercises[index]

    private val plannedNow = millis("2026-11-16T00:00:00Z")

    // ------------------------------------------------------------------ recognising the file

    @Test
    fun `anything that is not a Hevy export is not recognised`() {
        listOf(
            ImporterFixtures.bytes(ByteArray(0)),
            ImporterFixtures.text("hello world\nthis is a shopping list\n"),
            ImporterFixtures.bytes(Random(seed = 248).nextBytes(4_096)),
            // Almost a workout export, without the set index.
            ImporterFixtures.text(
                "title,start_time,end_time,exercise_title,reps\nPush,\"1 Jan 2024, 10:00\",,Bench,5\n",
            ),
            // Almost a measurement export, without a weight column it can read.
            ImporterFixtures.text("date,fat_percent,weight\n\"1 Jan 2024, 10:00\",15,80\n"),
            ImporterFixtures.text("\"title,start_time,exercise_title,set_index\n"),
        ).forEachIndexed { index, source ->
            assertEquals("source $index", ParseResult.NotRecognised, ImporterFixtures.read(parser, source))
        }
    }

    @Test
    fun `no Strong export is a Hevy export`() {
        listOf(
            "strong-ios.csv",
            "strong-android.csv",
            "strong-unit-columns.csv",
            "strong-early.csv",
            "strong-damaged.csv",
            "strong-header-only.csv",
        ).forEach { sample ->
            assertEquals(
                sample,
                ParseResult.NotRecognised,
                ImporterFixtures.read(parser, ImporterFixtures.resource("strong/$sample")),
            )
        }
    }

    @Test
    fun `no FitNotes export, nor its backup, is a Hevy export`() {
        listOf(
            "fitnotes-export.csv",
            "fitnotes-export-legacy.csv",
            "fitnotes-ios.csv",
            "fitnotes-body-tracker.csv",
            "fitnotes-damaged.csv",
            "FitNotes_Backup.fitnotes",
        ).forEach { sample ->
            assertEquals(
                sample,
                ParseResult.NotRecognised,
                ImporterFixtures.read(parser, ImporterFixtures.resource("fitnotes/$sample")),
            )
        }
    }

    @Test
    fun `no JEFIT export is a Hevy export`() {
        listOf("jefit-export.csv", "jefit-export-2024.csv", "jefit-damaged.csv").forEach { sample ->
            assertEquals(
                sample,
                ParseResult.NotRecognised,
                ImporterFixtures.read(parser, ImporterFixtures.resource("jefit/$sample")),
            )
        }
    }

    @Test
    fun `no Fitbod export is a Hevy export`() {
        listOf(
            "fitbod-export.csv",
            "fitbod-export-resaved.csv",
            "fitbod-support-export.csv",
            "fitbod-damaged.csv",
        ).forEach { sample ->
            assertEquals(
                sample,
                ParseResult.NotRecognised,
                ImporterFixtures.read(parser, ImporterFixtures.resource("fitbod/$sample")),
            )
        }
    }

    @Test
    fun `an export with no workouts or no measurements in it is an empty history, not a broken file`() {
        assertEquals(ImportedHistory(), parsedText("title,start_time,end_time,exercise_title,set_index,set_type\n"))
        assertEquals(ImportedHistory(), parsedText("date,weight_kg,fat_percent,waist_cm\n"))
    }

    // ------------------------------------------------------------------ the app's workout export

    @Test
    fun `an app export becomes its workouts, in file order, each starting at its time and lasting until its end`() {
        val workouts = parsed("hevy-workouts.csv").workouts

        assertEquals(listOf("Push A", "Morning Run", "Legs"), workouts.map(ImportedWorkout::name))
        assertEquals(
            listOf(millis("2026-10-28T18:04:00Z"), millis("2026-10-26T07:30:00Z"), millis("2026-10-04T18:00:00Z")),
            workouts.map(ImportedWorkout::startedAt),
        )
        assertEquals(listOf(3_720L, 2_280L, 3_600L), workouts.map(ImportedWorkout::durationSeconds))
        assertEquals("Slept badly\nstill moved well", workouts[0].notes)
        assertNull(workouts[1].notes)
    }

    @Test
    fun `set types and RPE are read, and a blank cell is not recorded`() {
        val bench = parsed("hevy-workouts.csv").workouts[0].line(0)

        assertEquals(
            listOf(
                ImportedSet(setType = SetType.WARMUP, reps = 8, weight = 40_000_000),
                ImportedSet(reps = 5, weight = 80_000_000, rpe = 80),
                ImportedSet(reps = 5, weight = 80_000_000, rpe = 85),
                ImportedSet(setType = SetType.FAILURE, reps = 3, weight = 80_000_000, rpe = 100),
                ImportedSet(setType = SetType.DROP_SET, reps = 8, weight = 60_000_000),
            ),
            bench.sets,
        )
        assertEquals("Pause the first rep", bench.notes)
        assertNull(bench.supersetGroup)
    }

    @Test
    fun `an exercise's name maps onto the catalogue, with the equipment its parenthesis names`() {
        val push = parsed("hevy-workouts.csv").workouts[0]

        assertEquals(
            ImportedExercise(
                name = "Bench Press (Barbell)",
                catalogueName = "Barbell Bench Press - Medium Grip",
                equipment = EquipmentType.BARBELL,
            ),
            push.line(0).exercise,
        )
        assertEquals(
            ImportedExercise(name = "Triceps Rope Pushdown", catalogueName = "Triceps Pushdown - Rope Attachment"),
            push.line(2).exercise,
        )
    }

    @Test
    fun `exercises sharing a superset id share a group, and a note repeated on every row is noted once`() {
        val push = parsed("hevy-workouts.csv").workouts[0]

        val raise = push.line(1)
        val pushdown = push.line(2)
        assertNotNull(raise.supersetGroup)
        assertEquals(raise.supersetGroup, pushdown.supersetGroup)
        assertEquals("Elbows pinned", pushdown.notes)
        assertEquals(listOf(25_000_000L, 25_500_000L), pushdown.sets.map(ImportedSet::weight))
    }

    @Test
    fun `an assisted exercise keeps its reps and drops the assistance, a bodyweight one records no weight`() {
        val push = parsed("hevy-workouts.csv").workouts[0]

        assertEquals("Pull Up (Assisted)", push.line(3).exercise.name)
        assertEquals(ImportedSet(reps = 8), push.line(3).sets.single())
        assertEquals(ImportedSet(reps = 10), push.line(4).sets.single())
    }

    @Test
    fun `a band-assisted pull-up drops its band like an assisted one`() {
        val csv = "title,start_time,exercise_title,set_index,weight_kg,reps\n" +
            "W,\"1 Jan 2024, 10:00\",Pull Up (Band),0,15,8\n"

        assertEquals(ImportedSet(reps = 8), parsedText(csv).workouts.single().line(0).sets.single())
    }

    @Test
    fun `an assisted exercise's assistance cannot cost its set, even when it is not a number`() {
        val csv = "title,start_time,exercise_title,set_index,weight_kg,reps\n" +
            "W,\"1 Jan 2024, 10:00\",Pull Up (Assisted),0,lots,8\n"

        assertEquals(ImportedSet(reps = 8), parsedText(csv).workouts.single().line(0).sets.single())
    }

    @Test
    fun `timed and distance sets keep their seconds and distance`() {
        val workouts = parsed("hevy-workouts.csv").workouts

        assertEquals(ImportedSet(duration = 60), workouts[0].line(5).sets.single())
        assertEquals(ImportedSet(duration = 1_860, distance = 520_000), workouts[1].line(0).sets.single())
    }

    @Test
    fun `an exercise done again, straight after or later, is another exercise of the same source exercise`() {
        val legs = parsed("hevy-workouts.csv").workouts[2]

        assertEquals(
            listOf(
                "Squat (Barbell)",
                "Romanian Deadlift (Barbell)",
                "Leg Extension (Machine)",
                "Leg Extension (Machine)",
                "Squat (Barbell)",
            ),
            legs.exercises.map { it.exercise.name },
        )
        assertEquals(listOf(2, 1, 2, 1, 1), legs.exercises.map { it.sets.size })
        assertTrue(legs.line(0).exercise === legs.line(4).exercise)
        assertEquals("Belt on\nheavy sets", legs.line(0).notes)
        assertNull(legs.line(4).notes)
    }

    @Test
    fun `a time is read in the zone the user is in`() {
        val workouts = parsed("hevy-workouts.csv", ImporterFixtures.context("America/New_York")).workouts

        assertEquals(millis("2026-10-28T22:04:00Z"), workouts[0].startedAt)
        assertEquals(3_720L, workouts[0].durationSeconds)
    }

    @Test
    fun `a unit in the header wins over the user's choice`() {
        val bench = parsed("hevy-workouts.csv", IMPERIAL_UTC).workouts[0].line(0)

        assertEquals(80_000_000L, bench.sets[1].weight)
    }

    // ------------------------------------------------------------------ the website's export

    @Test
    fun `a website export reads its US dates, its pounds and miles, and its quoted line breaks`() {
        val workouts = parsed("hevy-workouts-web.csv").workouts

        val upper = workouts[0]
        assertEquals(millis("2026-10-28T18:04:00Z"), upper.startedAt)
        assertEquals(3_960L, upper.durationSeconds)
        assertEquals("Felt strong.\nNew gym.", upper.notes)
        assertEquals(
            ImportedExercise(
                name = "Chest Press (Machine)",
                catalogueName = "Machine Bench Press",
                equipment = EquipmentType.MACHINE,
            ),
            upper.line(0).exercise,
        )
        assertEquals(
            listOf(ImportedSet(reps = 10, weight = 54_431_084), ImportedSet(reps = 8, weight = 58_967_008, rpe = 90)),
            upper.line(0).sets,
        )
        assertEquals("Rope, high pulley\nPull to the forehead", upper.line(2).notes)

        // 12:15 AM, after a narrow no-break space, is a quarter past midnight.
        val cardio = workouts[1]
        assertEquals(millis("2026-10-30T00:15:00Z"), cardio.startedAt)
        assertEquals(ImportedSet(duration = 1_800, distance = 201_168), cardio.line(0).sets.single())
    }

    @Test
    fun `superset ids are kept apart, and an exercise in another superset is another exercise`() {
        val upper = parsed("hevy-workouts-web.csv").workouts[0]

        assertEquals(
            listOf(
                "Chest Press (Machine)",
                "Seated Cable Row - V Grip (Cable)",
                "Face Pull",
                "Seated Cable Row - V Grip (Cable)",
            ),
            upper.exercises.map { it.exercise.name },
        )
        val groups = upper.exercises.map { it.supersetGroup }
        assertNull(groups[0])
        assertEquals(groups[1], groups[2])
        assertTrue(groups[3] != null && groups[3] != groups[1])
        assertEquals(75, upper.line(3).sets.single().rpe)
    }

    @Test
    fun `a superset id a spreadsheet re-saved as a decimal is still the same superset`() {
        val csv = """
            title,start_time,exercise_title,superset_id,set_index,weight_kg,reps
            W,"1 Jan 2024, 10:00",Bench Press (Barbell),1,0,100,5
            W,"1 Jan 2024, 10:00",Bent Over Row (Barbell),1.0,0,80,8
            W,"1 Jan 2024, 10:00",Plank,superset-a,0,,
        """.trimIndent()

        val groups = parsedText(csv).workouts.single().exercises.map { it.supersetGroup }
        assertEquals(groups[0], groups[1])
        assertTrue(groups[2] != null && groups[2] != groups[0])
    }

    @Test
    fun `a set index a spreadsheet re-saved as a decimal still tells an exercise done again apart`() {
        val csv = """
            title,start_time,exercise_title,set_index,weight_kg,reps
            W,"1 Jan 2024, 10:00",Leg Extension (Machine),0.0,40,12
            W,"1 Jan 2024, 10:00",Leg Extension (Machine),1.0,40,12
            W,"1 Jan 2024, 10:00",Leg Extension (Machine),0.0,30,15
        """.trimIndent()

        assertEquals(listOf(2, 1), parsedText(csv).workouts.single().exercises.map { it.sets.size })
    }

    @Test
    fun `a file carrying both units reads whichever column the row filled`() {
        val csv = """
            title,start_time,exercise_title,set_index,weight_kg,weight_lbs,reps,distance_meters
            W,"1 Jan 2024, 10:00",Bench Press (Barbell),0,100,,5,
            W,"1 Jan 2024, 10:00",Bench Press (Barbell),1,,225,5,
            W,"1 Jan 2024, 10:00",Running,0,,,,400
        """.trimIndent()

        val workout = parsedText(csv).workouts.single()
        assertEquals(listOf(100_000_000L, 102_058_283L), workout.line(0).sets.map(ImportedSet::weight))
        assertEquals(40_000L, workout.line(1).sets.single().distance)
    }

    // ------------------------------------------------------------------ another language

    @Test
    fun `a German app export reads its German dates, and its translated names are matched by name alone`() {
        val workout = parsed("hevy-workouts-de.csv").workouts.single()

        assertEquals("Oberkörper", workout.name)
        assertEquals(millis("2026-10-13T18:04:00Z"), workout.startedAt)
        assertEquals(3_360L, workout.durationSeconds)
        assertEquals(ImportedExercise(name = "Bankdrücken (Langhantel)"), workout.line(0).exercise)
        assertEquals(listOf(80_000_000L, 82_500_000L), workout.line(0).sets.map(ImportedSet::weight))
    }

    // ------------------------------------------------------------------ the measurement export

    @Test
    fun `a measurement export becomes body measurements in Titan's units, both sides read as the larger`() {
        val measurements = parsed("hevy-measurements.csv").bodyMeasurements

        assertEquals(
            listOf(
                BodyMeasurement(
                    weight = 81_873_423,
                    createdAt = millis("2026-06-24T00:00:00Z"),
                    updatedAt = millis("2026-06-24T00:00:00Z"),
                ),
                BodyMeasurement(
                    weight = 80_739_442,
                    neck = 381,
                    shoulders = 1_219,
                    chest = 1_029,
                    waist = 826,
                    hips = 965,
                    arm = 368,
                    forearm = 292,
                    thigh = 597,
                    calf = 381,
                    bodyFat = 155,
                    createdAt = millis("2026-07-01T08:30:00Z"),
                    updatedAt = millis("2026-07-01T08:30:00Z"),
                ),
                // The row with nothing in it is not an entry.
                BodyMeasurement(
                    waist = 787,
                    createdAt = millis("2026-07-15T00:00:00Z"),
                    updatedAt = millis("2026-07-15T00:00:00Z"),
                ),
            ),
            measurements,
        )
    }

    @Test
    fun `measurements in kilograms and centimetres are read in them, and the abdomen alone is no entry`() {
        val csv = """
            date,weight_kg,fat_percent,waist_cm,abdomen_cm,left_calf_cm,right_calf_cm
            "1 Jul 2026, 08:30",82.5,16.2,81.5,,,38
            "2 Jul 2026, 08:30",,,,90,,
        """.trimIndent()

        val entry = parsedText(csv, IMPERIAL_UTC).bodyMeasurements.single()
        assertEquals(82_500_000L, entry.weight)
        assertEquals(162, entry.bodyFat)
        assertEquals(815, entry.waist)
        assertEquals(380, entry.calf)
    }

    @Test
    fun `a measurement with a figure that is not a number is passed on empty, for the planner to count`() {
        val csv = """
            date,weight_kg,fat_percent,waist_cm
            "1 Jul 2026, 08:30",82.5,lean,81.5
            "2 Jul 2026, 08:30",82.5,16,81.5
            "last week",82.5,16,81.5
        """.trimIndent()

        val history = parsedText(csv)
        assertEquals(2, history.bodyMeasurements.size)
        assertTrue(history.bodyMeasurements[0].isEmpty)

        val plan = ImportPlanner.plan(history, ImporterFixtures.freshInstall(plannedNow))
        assertEquals(1, plan.measurements.size)
        assertEquals(1, plan.skipped.invalidMeasurements)
    }

    @Test
    fun `a measurement file whose every date is unreadable is malformed`() {
        val result = read("date,weight_kg,fat_percent\nlast week,80,15\n,81,15\n")

        assertTrue(result.toString(), result is ParseResult.Malformed)
    }

    // ------------------------------------------------------------------ damaged files

    @Test
    fun `a bad row costs that row, and a bad number costs its set, which the planner then counts`() {
        val good = parsed("hevy-damaged.csv").workouts.single()

        // "yesterday evening" and the row with no start are lost; so is the row with no exercise.
        assertEquals("Good", good.name)
        assertEquals(
            listOf(
                ImportedSet(reps = 5, weight = 100_000_000),
                // "heavy" is not a weight: nothing recorded, rather than a bodyweight set.
                ImportedSet(),
                // "hard" is not an RPE: the set stands without one.
                ImportedSet(reps = 5, weight = 100_000_000),
                // A row cut short after its set index.
                ImportedSet(),
                // A set type this parser does not know reads as a working set.
                ImportedSet(reps = 5, weight = 100_000_000),
                // "a while" is not a duration.
                ImportedSet(),
            ),
            good.line(0).sets,
        )
    }

    @Test
    fun `the sets a damaged file loses are counted in the report, not dropped in silence`() {
        val plan = ImportPlanner.plan(parsed("hevy-damaged.csv"), ImporterFixtures.freshInstall(plannedNow))

        assertEquals(3, plan.skipped.invalidSets)
        assertEquals(3, plan.setCount)
    }

    @Test
    fun `an end before the start, or one that does not read, leaves the duration to the planner`() {
        val csv = """
            title,start_time,end_time,exercise_title,set_index,weight_kg,reps
            A,"1 Jan 2024, 10:00","1 Jan 2024, 09:00",Bench Press (Barbell),0,100,5
            B,"2 Jan 2024, 10:00",soon,Bench Press (Barbell),0,100,5
        """.trimIndent()

        assertEquals(listOf(null, null), parsedText(csv).workouts.map(ImportedWorkout::durationSeconds))
    }

    @Test
    fun `two runs with the same title and times are two workouts, as Hevy wrote them`() {
        val csv = """
            title,start_time,end_time,exercise_title,set_index,weight_kg,reps
            A,"1 Jan 2024, 10:00","1 Jan 2024, 11:00",Bench Press (Barbell),0,100,5
            B,"2 Jan 2024, 10:00","2 Jan 2024, 11:00",Bench Press (Barbell),0,100,5
            A,"1 Jan 2024, 10:00","1 Jan 2024, 11:00",Bench Press (Barbell),0,100,5
        """.trimIndent()

        val history = parsedText(csv)
        assertEquals(3, history.workouts.size)
        // Starting in the same minute, the planner keeps the first.
        assertEquals(
            1,
            ImportPlanner.plan(history, ImporterFixtures.freshInstall(plannedNow)).skipped.duplicateWorkouts,
        )
    }

    @Test
    fun `a file whose every row is unreadable is malformed`() {
        val result = read("title,start_time,exercise_title,set_index\nPush,last week,Bench,0\nPush,,Bench,1\n")

        assertTrue(result.toString(), result is ParseResult.Malformed)
    }

    @Test
    fun `CSV that breaks past the header is malformed, naming the line`() {
        val result = read(
            "title,start_time,exercise_title,set_index\n" +
                "Push,\"1 Jan 2024, 10:00\",Bench,0\n" +
                "Push,\"1 Jan 2024, 10:00\",\"Bench,1\n",
        )

        assertTrue(result.toString(), result is ParseResult.Malformed && "line 3" in result.reason)
    }

    @Test
    fun `hostile text in names and notes is carried as text`() {
        val csv = "title,start_time,description,exercise_title,exercise_notes,set_index,weight_kg,reps\n" +
            "\"'; DROP TABLE session; --\",\"1 Jan 2024, 10:00\",\"<script>\u0000</script>\"," +
            "\"Bench Press (Barbell)‮\",\"\"\"quoted\"\" 💪\",0,100,5\n"

        val workout = parsedText(csv).workouts.single()
        assertEquals("'; DROP TABLE session; --", workout.name)
        assertEquals("<script>\u0000</script>", workout.notes)
        assertEquals("\"quoted\" 💪", workout.line(0).notes)
        assertEquals("Bench Press (Barbell)‮", workout.line(0).exercise.name)
    }

    // ------------------------------------------------------------------ through the planner

    @Test
    fun `a parsed app export plans cleanly against a fresh install, onto the catalogue's own exercises`() {
        val plan = ImportPlanner.plan(parsed("hevy-workouts.csv"), ImporterFixtures.freshInstall(plannedNow))
        val catalogue = ImporterFixtures.catalogue.associateBy { it.name }
        fun existing(name: String) = ExerciseRef.Existing(catalogue.getValue(name).id)

        assertTrue(plan.skipped.toString(), plan.skipped.isEmpty)
        assertEquals(listOf("Legs", "Morning Run", "Push A"), plan.workouts.map { it.name })

        val legs = plan.workouts[0]
        assertEquals(
            listOf(
                "Barbell Squat",
                "Romanian Deadlift",
                "Leg Extensions",
                "Leg Extensions",
                "Barbell Squat",
            ).map(::existing),
            legs.exercises.map { it.exercise },
        )

        val push = plan.workouts[2]
        assertEquals(push.startedAt + 3_720_000, push.finishedAt)
        assertEquals(
            listOf("Barbell Bench Press - Medium Grip", "Side Lateral Raise", "Triceps Pushdown - Rope Attachment")
                .map(::existing),
            push.exercises.take(3).map { it.exercise },
        )
        assertEquals(existing("Pullups"), push.exercises[4].exercise)
        assertEquals(
            listOf(SetType.WARMUP, SetType.NORMAL, SetType.NORMAL, SetType.FAILURE, SetType.DROP_SET),
            push.exercises[0].sets.map { it.setType },
        )
        val groups = push.exercises.map { it.supersetGroup }
        assertNotNull(groups[1])
        assertEquals(groups[1], groups[2])
        assertEquals(listOf(null, null, null), listOf(groups[0], groups[3], groups[4]))

        // The assisted pull-up, the timed plank (the catalogue's is rep-counted) and the run (no
        // plain run in the catalogue) are the user's own, created once each.
        val created = plan.newExercises.associate { it.exercise.name to it.exercise.type }
        assertEquals(
            mapOf(
                "Pull Up (Assisted)" to ExerciseType.STRENGTH,
                "Plank" to ExerciseType.ISOMETRIC,
                "Running" to ExerciseType.ENDURANCE,
            ),
            created,
        )
    }

    @Test
    fun `a website export plans cleanly too, onto the rows Hevy's own names map to`() {
        val plan = ImportPlanner.plan(parsed("hevy-workouts-web.csv"), ImporterFixtures.freshInstall(plannedNow))
        val catalogue = ImporterFixtures.catalogue.associateBy { it.name }

        assertTrue(plan.skipped.toString(), plan.skipped.isEmpty)
        assertEquals(
            listOf("Machine Bench Press", "Seated Cable Rows", "Face Pull", "Seated Cable Rows")
                .map { ExerciseRef.Existing(catalogue.getValue(it).id) },
            plan.workouts[0].exercises.map { it.exercise },
        )
        assertEquals(
            ExerciseRef.Existing(catalogue.getValue("Rowing, Stationary").id),
            plan.workouts[1].exercises.single().exercise,
        )
        assertTrue(plan.newExercises.isEmpty())
    }

    @Test
    fun `a measurement export plans into measurements, and an import run twice writes them once`() {
        val history = parsed("hevy-measurements.csv")
        val first = ImportPlanner.plan(history, ImporterFixtures.freshInstall(plannedNow))

        assertTrue(first.skipped.toString(), first.skipped.isEmpty)
        assertEquals(3, first.measurements.size)

        val again = ImportPlanner.plan(
            history,
            ImporterFixtures.freshInstall(
                plannedNow,
            ).copy(measurementTimes = first.measurements.map { it.createdAt }.toSet()),
        )
        assertEquals(3, again.skipped.duplicateMeasurements)
        assertTrue(again.measurements.isEmpty())
    }
}
