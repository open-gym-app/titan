package com.titan.core.domain.importer.jefit

import com.titan.core.domain.importer.ExerciseRef
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportPlanner
import com.titan.core.domain.importer.ImportedExercise
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ImportedTemplate
import com.titan.core.domain.importer.ImportedTemplateExercise
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImporterFixtures
import com.titan.core.domain.importer.ImporterFixtures.IMPERIAL_UTC
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.SetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * The JEFIT parser over sample exports in the layout JEFIT writes now and the one it wrote in
 * 2024, and over the files it has to turn away or survive.
 */
class JefitParserTest {

    private val parser = JefitParser()

    private fun parsed(
        sample: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.resource("jefit/$sample"), context)

    private fun parsedText(
        text: String,
        context: ImportContext = ImporterFixtures.METRIC_UTC,
    ): ImportedHistory = ImporterFixtures.parsed(parser, ImporterFixtures.text(text), context)

    private fun read(text: String): ParseResult = ImporterFixtures.read(parser, ImporterFixtures.text(text))

    private fun millis(instant: String) = Instant.parse(instant).toEpochMilli()

    private fun ImportedWorkout.names() = exercises.map { it.exercise.name }

    private val plannedNow = millis("2026-11-16T00:00:00Z")

    /** An export of [sections], each a name and the lines under its marker. */
    private fun export(vararg sections: Pair<String, String>): String =
        sections.joinToString("") { (name, body) -> "### $name ######\n$body\n######################\n" }

    private val logsHeader = "USERID,TIMESTAMP,belongSys,logs,_id,record,mydate,eid,ename,day_item_id,belongsession,logTime,interval_logs"

    /** An export whose only content is EXERCISE LOGS rows under [logsHeader], all on one day with no session. */
    private fun logs(vararg rows: String): String =
        export(
            "SETTING" to "row_id,USERID,length,mass\n1,42, cm, kg",
            "EXERCISE LOGS" to (listOf(logsHeader) + rows).joinToString("\n"),
        )

    /** The sets of the only exercise of the only workout [text] holds. */
    private fun onlySets(text: String): List<ImportedSet> = parsedText(text).workouts.single().exercises.single().sets

    // ------------------------------------------------------------------ recognising the file

    @Test
    fun `anything that is not a JEFIT export is not recognised`() {
        listOf(
            ImporterFixtures.bytes(ByteArray(0)),
            ImporterFixtures.text("hello world\nthis is a shopping list\n"),
            ImporterFixtures.bytes(Random(seed = 250).nextBytes(4_096)),
            // JEFIT's columns without its sections.
            ImporterFixtures.text("$logsHeader\n42,x,1,100x5,1,0,2026-10-05,2,Bench,0,0,0,\n"),
            // Sections, none of them JEFIT's.
            ImporterFixtures.text("### ORDERS ###\nid,total\n1,10\n###\n### CUSTOMERS ###\nid,name\n1,Ann\n###\n"),
            // A marker after the first line is not a sectioned file.
            ImporterFixtures.text("a,b\n### EXERCISE LOGS ###\n$logsHeader\n"),
            ImporterFixtures.text("\"### EXERCISE LOGS ###\n"),
        ).forEachIndexed { index, source ->
            assertEquals("source $index", ParseResult.NotRecognised, ImporterFixtures.read(parser, source))
        }
    }

    @Test
    fun `no Strong, Hevy or FitNotes export is a JEFIT export`() {
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
        ).forEach { sample ->
            assertEquals(
                sample,
                ParseResult.NotRecognised,
                ImporterFixtures.read(parser, ImporterFixtures.resource(sample)),
            )
        }
    }

    @Test
    fun `an export with nothing in its sections is an empty history, not a broken file`() {
        assertEquals(
            ImportedHistory(),
            parsedText(export("SETTING" to "", "EXERCISE LOGS" to "", "FAVORITE EXERCISES" to "")),
        )
        assertEquals(ImportedHistory(), parsedText(logs()))
        // A row of nothing: the all-zero body stats JEFIT creates with the account, and a log with no sets.
        assertEquals(
            ImportedHistory(),
            parsedText(
                export(
                    "PROFILE" to "_id,mydate,weight,chest\n1,2026-09-01,0,0",
                    "EXERCISE LOGS" to "$logsHeader\n42,x,1,,1,0,2026-10-05,2,Bench,0,0,0,",
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ workouts

    @Test
    fun `sessions are workouts with their own start, length, calories and day's name`() {
        val workouts = parsed("jefit-export.csv").workouts

        assertEquals(
            listOf(
                millis("2026-10-05T18:00:00Z"),
                millis("2026-10-08T17:30:00Z"),
                millis("2026-10-12T12:00:00Z"),
                millis("2026-10-14T12:00:00Z"),
            ),
            workouts.map(ImportedWorkout::startedAt),
        )
        // total_time, else endtime - starttime; nothing for a day with no session.
        assertEquals(listOf(3_900L, 3_600L, null, null), workouts.map(ImportedWorkout::durationSeconds))
        assertEquals(listOf("Upper", "Lower", null, null), workouts.map(ImportedWorkout::name))
        assertEquals(listOf(350, null, null, null), workouts.map(ImportedWorkout::calories))
        assertEquals(
            listOf(
                "Barbell Bench Press",
                "Cable Lat Pulldown (Wide Grip)",
                "Dumbbell Alternating Hammer Curl",
                "Plank",
                "Machine Assisted Pull-Up",
            ),
            workouts[0].names(),
        )
        // The squat done twice is two exercises, as JEFIT counts it, and the Deadlift's sets come
        // from its set rows alone.
        assertEquals(
            listOf("Barbell Squat", "Sled Push", "Treadmill Running", "Barbell Squat", "Push-Up", "Barbell Deadlift"),
            workouts[1].names(),
        )
    }

    @Test
    fun `logs no session places are the workout of their day, and a session without logs is left out`() {
        val workouts = parsed("jefit-export.csv").workouts

        // Session 4 has no start, and log 13 names no session: one workout at noon on their day.
        assertEquals(listOf("Push-Up", "Bodyweight Wall Squat", "Running"), workouts[2].names())
        // Log 14's session is not in the file; log 15 recorded no set, so it is not an exercise.
        assertEquals(listOf("Running"), workouts[3].names())
        // Session 3, opened and closed again with nothing logged, is not a workout.
        assertTrue(workouts.none { it.startedAt == millis("2026-10-10T09:00:00Z") })
    }

    @Test
    fun `every set keeps its weight and reps in Titan's units, and a set row gives its set its type`() {
        val (upper, lower) = parsed("jefit-export.csv").workouts

        assertEquals(
            listOf(
                ImportedSet(setType = SetType.WARMUP, reps = 10, weight = 60_000_000),
                ImportedSet(reps = 5, weight = 80_000_000),
                ImportedSet(reps = 5, weight = 80_000_000),
                ImportedSet(reps = 3, weight = 82_500_000),
            ),
            upper.exercises[0].sets,
        )
        // A trailing comma ends the list.
        assertEquals(listOf(10, 10, 12), upper.exercises[1].sets.map { it.reps })
        assertEquals(listOf(SetType.NORMAL, SetType.DROP_SET), upper.exercises[2].sets.map { it.setType })
        // A bodyweight set is a zero weight, as JEFIT writes it.
        assertEquals(
            listOf(ImportedSet(reps = 15, weight = 0), ImportedSet(reps = 12, weight = 0)),
            lower.exercises[4].sets,
        )
        // No packed sets: the set row is the set, its pounds put back in kilograms to the gram.
        assertEquals(listOf(ImportedSet(reps = 5, weight = 100_000_000)), lower.exercises[5].sets)
    }

    @Test
    fun `cardio keeps its distance and time, from the set rows' miles where they have them`() {
        val workouts = parsed("jefit-export.csv").workouts

        // 2 mi from the set row, though the packed string recorded no distance.
        assertEquals(listOf(ImportedSet(duration = 1_200, distance = 321_869)), workouts[1].exercises[2].sets)
        // Packed distances follow the account's units: kilometres beside centimetres.
        assertEquals(listOf(ImportedSet(duration = 1_800, distance = 310_000)), workouts[2].exercises[2].sets)
        assertEquals(listOf(ImportedSet(duration = 1_500, distance = 500_000)), workouts[3].exercises[0].sets)
    }

    @Test
    fun `holds are timed sets, and an assisted exercise keeps its reps and drops the counterweight`() {
        val workouts = parsed("jefit-export.csv").workouts

        // The plank packs its seconds as cardio does, and nothing else says it is cardio.
        assertEquals(listOf(ImportedSet(duration = 60), ImportedSet(duration = 45)), workouts[0].exercises[3].sets)
        assertEquals(listOf(ImportedSet(reps = 8), ImportedSet(reps = 6)), workouts[0].exercises[4].sets)
        // The wall sit is `0x0` with its seconds in the interval timer.
        assertEquals(listOf(ImportedSet(weight = 0, duration = 75)), workouts[2].exercises[1].sets)

        // The counterweight is dropped unread, so one that does not read costs the set nothing.
        assertEquals(
            listOf(ImportedSet(reps = 8), ImportedSet(reps = 6), ImportedSet()),
            onlySets(logs("42,x,1,\"heavyx8,x6,40xmany\",1,0,2026-10-05,290,Machine Assisted Pull-Up,0,0,0,")),
        )
    }

    @Test
    fun `an exercise is one exercise across its renames, named, mapped and described from every section`() {
        val history = parsed("jefit-export.csv")
        val (upper, lower, day) = history.workouts

        assertEquals(
            ImportedExercise(
                name = "Barbell Bench Press",
                catalogueName = "Barbell Bench Press - Medium Grip",
                equipment = EquipmentType.BARBELL,
                primaryMuscle = "CHEST",
                defaultRestSeconds = 120,
            ),
            upper.exercises[0].exercise,
        )
        assertEquals(
            ImportedExercise(
                name = "Dumbbell Alternating Hammer Curl",
                catalogueName = "Alternate Hammer Curl",
                equipment = EquipmentType.DUMBBELL,
                primaryMuscle = "BICEPS",
                defaultRestSeconds = 60,
            ),
            upper.exercises[2].exercise,
        )
        // Cardio in JEFIT's body parts is endurance; a treadmill is a machine.
        assertEquals(
            ImportedExercise(
                name = "Treadmill Running",
                catalogueName = "Running, Treadmill",
                equipment = EquipmentType.MACHINE,
                type = ExerciseType.ENDURANCE,
            ),
            lower.exercises[2].exercise,
        )
        // "Push Up" was renamed "Push-Up": one exercise, under its latest name.
        assertEquals(lower.exercises[4].exercise, day.exercises[0].exercise)
        assertEquals(ImportedExercise(name = "Push-Up", catalogueName = "Pushups"), day.exercises[0].exercise)
        // The nameless log from a watch takes the name its id has elsewhere.
        assertEquals(ImportedExercise(name = "Running"), day.exercises[2].exercise)
        // The user's own exercise: their name, description and rest, and the one never trained.
        val sled = ImportedExercise(
            name = "Sled Push",
            notes = "Low handles, drive with the legs",
            defaultRestSeconds = 90,
        )
        assertEquals(sled, lower.exercises[1].exercise)
        assertEquals(
            listOf(
                sled,
                ImportedExercise(name = "Cable Y Raise", equipment = EquipmentType.CABLE, primaryMuscle = "SHOULDERS"),
            ),
            history.exercises,
        )
    }

    @Test
    fun `an exercise note is kept on the first log of its exercise that day`() {
        val (upper, lower) = parsed("jefit-export.csv").workouts

        assertEquals("Paused reps, \"tight\" arch", upper.exercises[0].notes)
        assertEquals(
            listOf("Belt from set 2", null),
            lower.exercises.filter { it.exercise.name == "Barbell Squat" }.map { it.notes },
        )
        assertTrue(upper.exercises.drop(1).all { it.notes == null })
    }

    @Test
    fun `a session keeps its instant in any zone, and a day starts at noon in the user's`() {
        val paris = parsed("jefit-export.csv", ImporterFixtures.context("Europe/Paris")).workouts
        val angeles = parsed("jefit-export.csv", ImporterFixtures.context("America/Los_Angeles")).workouts

        assertEquals(millis("2026-10-05T18:00:00Z"), paris[0].startedAt)
        assertEquals(millis("2026-10-05T18:00:00Z"), angeles[0].startedAt)
        assertEquals(millis("2026-10-12T10:00:00Z"), paris[2].startedAt)
        assertEquals(millis("2026-10-12T19:00:00Z"), angeles[2].startedAt)
    }

    @Test
    fun `the account's units win over the user's, and the user's stand in where the export says none`() {
        val imperialUser = parsed("jefit-export.csv", IMPERIAL_UTC).workouts
        assertEquals(60_000_000L, imperialUser[0].exercises[0].sets[0].weight)
        assertEquals(310_000L, imperialUser[2].exercises[2].sets.single().distance)

        val noSetting = export(
            "EXERCISE LOGS" to "$logsHeader\n42,x,1,100x5,1,0,2026-10-05,2,Bench,0,0,0,\n" +
                "42,x,1,\"0x0,0x2,0x0,0x0,0x600\",2,0,2026-10-05,317,Run,0,0,0,",
        )
        val metric = parsedText(noSetting).workouts.single().exercises
        assertEquals(100_000_000L, metric[0].sets.single().weight)
        assertEquals(200_000L, metric[1].sets.single().distance)
        val imperial = parsedText(noSetting, IMPERIAL_UTC).workouts.single().exercises
        assertEquals(45_359_237L, imperial[0].sets.single().weight)
        assertEquals(321_869L, imperial[1].sets.single().distance)
    }

    // ------------------------------------------------------------------ the 2024 layout

    @Test
    fun `a 2024 export reads its pounds, inches and miles, and tells cardio from its packing alone`() {
        val history = parsed("jefit-export-2024.csv")
        val (session, day) = history.workouts

        assertEquals(millis("2024-05-06T17:00:00Z"), session.startedAt)
        assertEquals(2_400L, session.durationSeconds)
        assertEquals("Chest and Triceps", session.name)
        assertEquals(
            listOf(ImportedSet(reps = 10, weight = 61_234_970), ImportedSet(reps = 5, weight = 83_914_588)),
            session.exercises[0].sets,
        )
        // An interval beside counted reps is not a time.
        assertEquals(listOf(ImportedSet(reps = 5, weight = 0)), session.exercises[1].sets)
        assertEquals(listOf(ImportedSet(duration = 62)), session.exercises[2].sets)

        // No sessions for these logs: noon on their day.
        assertEquals(millis("2024-05-09T12:00:00Z"), day.startedAt)
        assertEquals(listOf("Running", "Pull-Up", "Bodyweight Wall Squat", "inverted barbell row"), day.names())
        // 3.25 mi.
        assertEquals(listOf(ImportedSet(duration = 2_446, distance = 523_037)), day.exercises[0].sets)
        // Five bodyweight sets with a failed one are not cardio; the empty set is left out.
        assertEquals(listOf(10, 10, 8, 5), day.exercises[1].sets.map { it.reps })
        assertEquals("Last set to failure", day.exercises[1].notes)
        // No packed sets, one timed interval.
        assertEquals(listOf(ImportedSet(duration = 122)), day.exercises[2].sets)
        assertEquals(
            listOf(ImportedSet(reps = 12, weight = 20_411_657), ImportedSet(reps = 10, weight = 20_411_657)),
            day.exercises[3].sets,
        )

        val weighed = millis("2024-05-04T12:00:00Z")
        assertEquals(
            listOf(
                BodyMeasurement(
                    weight = 81_646_627,
                    chest = 1_016,
                    arm = 394,
                    createdAt = weighed,
                    updatedAt = weighed,
                ),
            ),
            history.bodyMeasurements,
        )
        assertEquals(listOf(ImportedExercise(name = "inverted barbell row")), history.exercises)
    }

    // ------------------------------------------------------------------ routines

    @Test
    fun `each day of a routine is a template, planning its sets in reps or in time`() {
        val bench = ImportedExercise(
            name = "Barbell Bench Press",
            catalogueName = "Barbell Bench Press - Medium Grip",
            equipment = EquipmentType.BARBELL,
            primaryMuscle = "CHEST",
            defaultRestSeconds = 120,
        )
        val templates = parsed("jefit-export.csv").templates

        assertEquals(
            listOf("Upper, Lower - Upper", "Upper, Lower - Lower", "Home Abs"),
            templates.map(ImportedTemplate::name),
        )
        val (upper, lower, abs) = templates
        assertEquals(
            ImportedTemplateExercise(
                bench,
                sets = listOf(PlannedSet(reps = 8), PlannedSet(reps = 6), PlannedSet(reps = 4)),
            ),
            upper.exercises[0],
        )
        // In mysort order; the plank is planned in its interval's seconds, not its leftover reps.
        assertEquals(
            listOf(
                "Barbell Bench Press",
                "Plank",
                "Cable Lat Pulldown (Wide Grip)",
                "Dumbbell Alternating Hammer Curl",
            ),
            upper.exercises.map { it.exercise.name },
        )
        assertEquals(List(3) { PlannedSet(duration = 45) }, upper.exercises[1].sets)
        // The superset, and one target for every set.
        assertEquals(listOf(null, null, 0, 0), upper.exercises.map { it.supersetGroup })
        assertEquals(List(2) { PlannedSet(reps = 12) }, upper.exercises[3].sets)

        assertEquals(List(4) { PlannedSet(reps = 5) }, lower.exercises[0].sets)
        assertEquals(List(3) { PlannedSet() }, lower.exercises[1].sets)
        // Twenty minutes on the treadmill.
        assertEquals(listOf(PlannedSet(duration = 1_200)), lower.exercises[2].sets)

        // A routine with one day is named after the routine.
        assertEquals(
            ImportedExercise(
                name = "Cable Crunch",
                equipment = EquipmentType.CABLE,
                primaryMuscle = "ABDOMINALS",
                defaultRestSeconds = 45,
            ),
            abs.exercises.single().exercise,
        )
    }

    @Test
    fun `an older routine plans one target for every set`() {
        val (template) = parsed("jefit-export-2024.csv").templates

        assertEquals("Starter Routine - Chest and Triceps", template.name)
        assertEquals(List(3) { PlannedSet(reps = 6) }, template.exercises[0].sets)
        // A lift with an interval timer is still planned in reps.
        assertEquals(List(3) { PlannedSet(reps = 8) }, template.exercises[1].sets)
    }

    // ------------------------------------------------------------------ body stats

    @Test
    fun `body stats are one measurement a day, in Titan's units, a later sync's figure winning`() {
        val noon = millis("2026-10-01T12:00:00Z")
        val later = millis("2026-10-15T12:00:00Z")

        assertEquals(
            listOf(
                BodyMeasurement(
                    weight = 82_400_000,
                    neck = 395,
                    shoulders = 1_200,
                    chest = 1_010,
                    waist = 840,
                    hips = 980,
                    arm = 385,
                    forearm = 300,
                    thigh = 580,
                    calf = 390,
                    bodyFat = 155,
                    createdAt = noon,
                    updatedAt = noon,
                ),
                BodyMeasurement(weight = 81_000_000, waist = 830, createdAt = later, updatedAt = later),
            ),
            parsed("jefit-export.csv").bodyMeasurements,
        )
    }

    // ------------------------------------------------------------------ packed sets

    @Test
    fun `a piece that does not read is a set with nothing in it, and an empty one is no set`() {
        assertEquals(
            listOf(
                ImportedSet(reps = 5, weight = 100_000_000),
                ImportedSet(),
                ImportedSet(),
                ImportedSet(),
                // A weight with no reps is a set that lost them, for the planner to refuse.
                ImportedSet(reps = 0, weight = 90_000_000),
                ImportedSet(reps = 8, weight = 62_500_000),
            ),
            onlySets(logs("42,x,1,\"100x5,abc,100x,1x2x3,0x0,90x0,62.5X8,,\",1,0,2026-10-05,2,Bench,0,0,0,")),
        )
        // A log of nothing but skipped sets, and no set rows to stand in, records nothing at all.
        val skipped = logs("42,x,1,\"0x0,0x0\",1,0,2026-10-05,2,Bench,0,0,0,")
        assertEquals(emptyList<ImportedWorkout>(), parsedText(skipped).workouts)
    }

    @Test
    fun `cardio is told from its packing only when nothing else could have written it`() {
        fun sets(packed: String) = onlySets(logs("42,x,1,\"$packed\",1,0,2026-10-05,9,Thing,0,0,0,"))

        assertEquals(listOf(ImportedSet(duration = 62)), sets("0x0,0x0,0x0,0x0,0x62"))
        assertEquals(listOf(ImportedSet(duration = 600, distance = 150_000)), sets("0x80,0x1.5,0x9,0x0,0x600"))
        // A skipped set, five zeros, says nothing either way: the hold is still a hold.
        assertEquals(
            listOf(ImportedSet(duration = 60)),
            sets("0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x60"),
        )
        // Five bodyweight sets, with no failed one, or not ending on the most.
        assertEquals(List(5) { ImportedSet(reps = 8, weight = 0) }, sets("0x8,0x8,0x8,0x8,0x8"))
        assertEquals(listOf(10, 10, 8, 5), sets("0x10,0x10,0x8,0x0,0x5").map { it.reps })
        // A weight anywhere is a lift.
        assertEquals(
            listOf(ImportedSet(reps = 0, weight = 5_000_000), ImportedSet(reps = 60, weight = 0)),
            sets("0x0,0x0,0x0,5x0,0x60"),
        )
    }

    @Test
    fun `cardio the export lists is read as cardio, and a group cut short is counted, not guessed at`() {
        val export = export(
            "EXERCISE LOGS" to "$logsHeader\n42,x,1,\"0x10,0x1,0x0,0x0,0x300,0x5\",1,0,2026-10-05,500,Swim,0,0,0,\n" +
                "42,x,1,\"0x0,0x0,0x0,0x0,0x400,0x0\",2,0,2026-10-05,500,Swim,0,0,0,\n" +
                // Another log of the same exercise is cardio too, though it looks like a lift.
                "42,x,1,\"0x0,0x0,0x0,0x0,0x400,0x0\",3,0,2026-10-06,500,Swim,0,0,0,",
            "CARDIO EXERCISE LOGS" to "userid,TIMESTAMP,cardio_logs,_id,el_id\n42,x,0x300,1,1",
        )

        val workouts = parsedText(export).workouts
        assertEquals(
            listOf(ImportedSet(duration = 300, distance = 100_000), ImportedSet()),
            workouts[0].exercises[0].sets,
        )
        assertEquals(listOf(ImportedSet(duration = 400)), workouts[0].exercises[1].sets)
        assertEquals(listOf(ImportedSet(duration = 400)), workouts[1].exercises.single().sets)
    }

    @Test
    fun `set rows are the sets when the packed string has none, and give no types when they do not line up`() {
        val setHeader = "_id,userid,exercise_log_id,set_index,weight_lbs,reps,calories,distance_mi,speed_mph,laps,duration,set_type"
        fun export(mass: String) =
            export(
                "SETTING" to "row_id,USERID,mass\n1,42, $mass",
                "EXERCISE LOGS" to
                    "$logsHeader\n42,x,1,,1,0,2026-10-05,2,Bench,0,0,0,\n42,x,1,\"80x5,80x5\",2,0,2026-10-05,3,Row,0,0,0,\n" +
                    "42,x,1,,3,0,2026-10-05,4,Bike,0,0,0,\n42,x,1,,4,0,2026-10-05,290,Machine Assisted Dip,0,0,0,\n" +
                    "42,x,1,0x0,5,0,2026-10-05,5,Curl,0,0,0,",
                "EXERCISE SET LOGS" to
                    "$setHeader\n1,42,1,1,88.1849,5,,,,,0,default\n2,42,1,0,88.1849,8,,,,,0,warm-up\n" +
                    "3,42,2,0,176.3698,5,,,,,0,drop\n4,42,3,0,,,,1.5,,,900,default\n5,42,3,1,,,,,,,,default\n6,42,4,0,50,10,,,,,,failure" +
                    "\n7,42,5,0,22.0462,12,,,,,0,default",
            )

        val metric = parsedText(export("kg")).workouts.single().exercises
        assertEquals(
            listOf(
                ImportedSet(setType = SetType.WARMUP, reps = 8, weight = 40_000_000),
                ImportedSet(reps = 5, weight = 40_000_000),
            ),
            metric[0].sets,
        )
        assertEquals(listOf(SetType.NORMAL, SetType.NORMAL), metric[1].sets.map { it.setType })
        // A row with neither reps nor time records nothing.
        assertEquals(listOf(ImportedSet(duration = 900, distance = 241_402)), metric[2].sets)
        assertEquals(listOf(ImportedSet(setType = SetType.FAILURE, reps = 10)), metric[3].sets)
        // A packed string of nothing but `0x0` holds no set, so its rows are the sets too.
        assertEquals(listOf(ImportedSet(reps = 12, weight = 10_000_000)), metric[4].sets)
        // An account in pounds keeps the pounds as written.
        assertEquals(39_999_998L, parsedText(export("lbs")).workouts.single().exercises[0].sets[0].weight)
    }

    // ------------------------------------------------------------------ damaged files

    @Test
    fun `a bad row costs that row, and a bad figure costs its set or measurement, which the planner counts`() {
        val history = parsed("jefit-damaged.csv")
        val workout = history.workouts.single()

        assertEquals(millis("2026-10-05T18:00:00Z"), workout.startedAt)
        // "soon" and "later" are no length, "lots" no calories.
        assertNull(workout.durationSeconds)
        assertNull(workout.calories)
        // The squat dated "last week", the log with no exercise and the table without logs are lost.
        assertEquals(listOf("Barbell Bench Press", "Running", "Treadmill Running"), workout.names())
        assertEquals(
            listOf(
                ImportedSet(reps = 5, weight = 80_000_000),
                ImportedSet(),
                ImportedSet(),
                ImportedSet(),
                ImportedSet(reps = 5, weight = 80_000_000),
            ),
            workout.exercises[0].sets,
        )
        assertEquals(
            listOf(ImportedSet(duration = 1_500, distance = 500_000), ImportedSet()),
            workout.exercises[1].sets,
        )
        assertEquals(listOf(ImportedSet(duration = 600)), workout.exercises[2].sets)

        // "yesterday" is lost; a day whose only figure is "heavy" is passed on empty; "lots" costs the chest.
        val first = millis("2026-10-01T12:00:00Z")
        val second = millis("2026-10-02T12:00:00Z")
        assertEquals(
            listOf(
                BodyMeasurement(createdAt = first, updatedAt = first),
                BodyMeasurement(weight = 80_000_000, createdAt = second, updatedAt = second),
            ),
            history.bodyMeasurements,
        )
        // A day of a routine not in the file, a set count no plan has, reps that are not numbers.
        assertEquals(
            listOf(
                ImportedTemplate(
                    "Orphan Day",
                    listOf(ImportedTemplateExercise(workout.exercises[0].exercise, List(2) { PlannedSet() })),
                ),
            ),
            history.templates,
        )
    }

    @Test
    fun `the sets and measurements a damaged file loses are counted in the report, not dropped in silence`() {
        val plan = ImportPlanner.plan(parsed("jefit-damaged.csv"), ImporterFixtures.freshInstall(plannedNow))

        assertEquals(4, plan.skipped.invalidSets)
        assertEquals(1, plan.skipped.invalidMeasurements)
        assertEquals(4, plan.setCount)
        assertEquals(1, plan.templates.size)
    }

    @Test
    fun `sections holding rows under no header JEFIT writes are malformed`() {
        val result = read(export("EXERCISE LOGS" to "id,when,what\n1,today,bench", "PROFILE" to "id,when\n1,today"))

        assertTrue(result.toString(), result is ParseResult.Malformed)
    }

    @Test
    fun `an export whose every log has no workout or date to go in is malformed`() {
        val result = read(logs("42,x,1,100x5,1,0,last week,2,Bench,0,7,0,", "42,x,1,100x5,2,0,,2,Bench,0,0,0,"))

        assertTrue(result.toString(), result is ParseResult.Malformed && "2 exercise logs" in result.reason)
    }

    @Test
    fun `CSV that breaks past the first marker is malformed, naming the line`() {
        val result = read("### EXERCISE LOGS ###\n$logsHeader\n42,x,1,\"100x5,1,0,2026-10-05,2,Bench,0,0,0,\n")

        assertTrue(result.toString(), result is ParseResult.Malformed && "line 3" in result.reason)
    }

    @Test
    fun `hostile text is carried as text and hostile figures are refused rather than believed`() {
        val text = export(
            "WORKOUT SESSIONS" to
                "rowid,_id,starttime,total_time\n1,1,99999999999999999,99999999999999999999\n2,2,1791223200,-5",
            "EXERCISE LOGS" to "$logsHeader\n42,x,1,100x5,1,0,2026-10-05,2,\"'; DROP TABLE session; --\",0,1,0,\n" +
                "42,x,1,100x5,2,0,2026-10-06,3,\"Bench Press‮\u0000\",0,2,0,",
            "NOTES" to "_id,eid,belongSys,mynote,mydate\n1,2,1,\"<script>\"\"x\"\"</script>\",2026-10-05",
            "ROUTINES" to "_id,name\n1,R\npackage,_id,name\n1,11,D\n" +
                "belongSys,_id,exercise_id,belongplan,exercisename,setcount,bodypart,interval_time,interval_unit\n" +
                "1,1,321,11,Treadmill Running,1,10,307445734561825920,min",
        )

        val (overflow, negative) = parsedText(text).workouts
        // A start too large to be an instant is no start: the log goes to its day.
        assertEquals(millis("2026-10-05T12:00:00Z"), overflow.startedAt)
        assertEquals("'; DROP TABLE session; --", overflow.exercises.single().exercise.name)
        assertEquals("<script>\"x\"</script>", overflow.exercises.single().notes)
        assertNull(negative.durationSeconds)
        assertEquals("Bench Press‮\u0000", negative.exercises.single().exercise.name)
        // Minutes that would overflow as seconds are no time, not a time wrapped round to an hour.
        assertEquals(listOf(PlannedSet()), parsedText(text).templates.single().exercises.single().sets)
    }

    // ------------------------------------------------------------------ through the planner

    @Test
    fun `a parsed export plans cleanly against a fresh install, onto the catalogue's own exercises`() {
        val plan = ImportPlanner.plan(parsed("jefit-export.csv"), ImporterFixtures.freshInstall(plannedNow))
        val catalogue = ImporterFixtures.catalogue.associateBy { it.name }
        fun existing(name: String) = ExerciseRef.Existing(catalogue.getValue(name).id)

        assertTrue(plan.skipped.toString(), plan.skipped.isEmpty)
        assertEquals(4, plan.workouts.size)
        val (upper, lower, day) = plan.workouts
        val run = plan.workouts[3]
        assertEquals(
            listOf(
                existing("Barbell Bench Press - Medium Grip"),
                existing("Wide-Grip Lat Pulldown"),
                existing("Alternate Hammer Curl"),
            ),
            upper.exercises.take(3).map { it.exercise },
        )
        assertEquals(upper.startedAt + 3_900_000, upper.finishedAt)
        assertEquals(350, upper.calories)
        // The user's own sled push is one the catalogue has, so it is matched rather than created.
        assertEquals(
            listOf(
                "Barbell Squat",
                "Sled Push",
                "Running, Treadmill",
                "Barbell Squat",
                "Pushups",
                "Barbell Deadlift",
            ).map(::existing),
            lower.exercises.map { it.exercise },
        )
        assertEquals(existing("Pushups"), day.exercises[0].exercise)
        assertEquals(day.exercises[2].exercise, run.exercises.single().exercise)

        val created = plan.newExercises.associate { it.exercise.name to (it.exercise.type to it.primaryMuscle?.name) }
        assertEquals(
            mapOf(
                "Plank" to (ExerciseType.ISOMETRIC to "ABDOMINALS"),
                "Machine Assisted Pull-Up" to (ExerciseType.STRENGTH to null),
                "Bodyweight Wall Squat" to (ExerciseType.ISOMETRIC to null),
                "Running" to (ExerciseType.ENDURANCE to null),
                "Cable Y Raise" to (ExerciseType.STRENGTH to "SHOULDERS"),
            ),
            created,
        )
        assertEquals(listOf("Upper, Lower - Upper", "Upper, Lower - Lower", "Home Abs"), plan.templates.map { it.name })
        assertEquals(existing("Cable Crunch"), plan.templates[2].exercises.single().exercise)
        assertEquals(2, plan.measurements.size)
    }

    @Test
    fun `importing the same file again is a duplicate of every workout, template and measurement`() {
        val history = parsed("jefit-export.csv")
        val first = ImportPlanner.plan(history, ImporterFixtures.freshInstall(plannedNow))

        val again = ImportPlanner.plan(
            history,
            ImporterFixtures.freshInstall(plannedNow).copy(
                sessionStarts = first.workouts.map { it.startedAt }.toSet(),
                measurementTimes = first.measurements.map { it.createdAt }.toSet(),
            ),
        )
        assertEquals(4, again.skipped.duplicateWorkouts)
        assertEquals(2, again.skipped.duplicateMeasurements)
        assertTrue(again.workouts.isEmpty())
    }

    @Test
    fun `a 2024 export plans cleanly too`() {
        val plan = ImportPlanner.plan(parsed("jefit-export-2024.csv"), ImporterFixtures.freshInstall(plannedNow))

        assertTrue(plan.skipped.toString(), plan.skipped.isEmpty)
        assertEquals(2, plan.workouts.size)
        assertEquals(12, plan.setCount)
        assertEquals(1, plan.templates.size)
        assertEquals(1, plan.measurements.size)
    }
}
