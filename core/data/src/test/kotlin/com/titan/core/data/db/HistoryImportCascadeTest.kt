package com.titan.core.data.db

import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.importer.HistoryImportError
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.importer.HistoryImportReport
import com.titan.core.domain.importer.HistoryParser
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportedExercise
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ImportedTemplate
import com.titan.core.domain.importer.ImportedTemplateExercise
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImportedWorkoutExercise
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.PrType
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

/**
 * The import engine over the database it writes to, with the completion pipeline it shares with a
 * finished workout.
 *
 * `ImportPlannerTest` pins every rule about *what* an import writes. What only a real store can
 * show is the rest: that the writes and the replay are one transaction a cancelled collector rolls
 * back, that the replay's records, XP and unlocks are the rows the screens read, that a second
 * import of the same file pays nothing, and that a history older than the device's re-derives the
 * device's records in the order the training happened — which is a `DELETE` this suite is the
 * only thing to run.
 */
class HistoryImportCascadeTest : RoomTestBase() {

    private val preferences = InMemoryUserPreferencesRepository()

    private val stack by lazy { HistoryImportStack(db, preferences, Clock { NOW }) }

    private val sessions get() = stack.sessions

    private val records get() = stack.records

    private val importHistory get() = stack.importHistory

    private var benchId = 0L

    @Before
    fun seedCatalogue() =
        runBlocking {
            benchId = db.exerciseDao().insert(exercise(name = "Bench Press"))
            db.muscleGroupDao().insertAll(listOf(muscleGroup(name = "CHEST")))
            Unit
        }

    private val bench = ImportedExercise(name = "bench press")

    private fun benchWorkout(
        day: Long,
        weight: Long,
    ) = ImportedWorkout(
        startedAt = day * DAY,
        durationSeconds = 3_600,
        name = "Push",
        exercises = listOf(
            ImportedWorkoutExercise(bench, sets = List(3) { ImportedSet(reps = 5, weight = weight) }),
        ),
    )

    private suspend fun import(history: ImportedHistory): List<HistoryImportProgress> = importHistory(history).toList()

    private fun List<HistoryImportProgress>.report(): HistoryImportReport =
        (last() as HistoryImportProgress.Complete).report

    private suspend fun totalXp(): Long = db.userProgressDao().observe().first()?.totalXp ?: 0L

    // ------------------------------------------------------------------ the whole import

    @Test
    fun `an imported history lands as finished sessions and pays as if it had been logged`() =
        runBlocking {
            val fly = ImportedExercise(name = "Cable Fly", primaryMuscle = "chest")
            val history = ImportedHistory(
                workouts = listOf(
                    benchWorkout(day = 1, weight = KG_100).copy(calories = 480, rating = 4),
                    benchWorkout(day = 8, weight = KG_110).let { workout ->
                        workout.copy(
                            exercises = workout.exercises + ImportedWorkoutExercise(
                                fly,
                                listOf(ImportedSet(reps = 12)),
                            ),
                        )
                    },
                ),
                templates = listOf(
                    ImportedTemplate("Push", listOf(ImportedTemplateExercise(bench, listOf(PlannedSet(reps = 5))))),
                ),
                bodyMeasurements = listOf(
                    BodyMeasurement(weight = 80_000_000, neck = 390, calf = 385, createdAt = DAY, updatedAt = DAY),
                ),
            )

            val progress = import(history)
            val report = progress.report()

            assertEquals(
                listOf(
                    HistoryImportProgress.Reading,
                    HistoryImportProgress.Writing(0, 2),
                    HistoryImportProgress.Writing(1, 2),
                    HistoryImportProgress.Writing(2, 2),
                ),
                progress.dropLast(1),
            )
            assertEquals(2, report.workoutsImported)
            assertEquals(7, report.setsImported)
            assertEquals(1, report.exercisesMatched)
            assertEquals(1, report.exercisesCreated)
            assertEquals(1, report.templatesImported)
            assertEquals(1, report.measurementsImported)

            val stored = sessions.getAll().first()
            assertEquals(2, stored.size)
            assertEquals(480 to 4, stored.minBy { it.createdAt }.let { it.calories to it.rating })
            assertTrue(stored.all { !it.isInProgress && it.completedAt != null })
            assertEquals(1, countRows("exercise_muscle_group"))
            val measured = db.bodyMeasurementDao().getAll().first().single()
            assertEquals(390 to 385, measured.neck to measured.calf)

            // The second workout beat the first: the latest heaviest bench is its 110 kg.
            val heaviest = records.getRecordsForExercise(benchId).first().filter { it.type == PrType.MAX_WEIGHT }
            assertEquals(listOf(KG_110, KG_100), heaviest.map { it.weight })

            // Paid through the real cascade, and reported as what the store now holds.
            assertTrue(report.xpEarned > 0)
            assertEquals(report.xpEarned, totalXp())
            assertEquals(2, countRows("session_xp_claim"))
            assertEquals(report.achievementsUnlocked, countRows("achievement"))
            assertEquals(report.questsCompleted, countRows("quest_completion"))
        }

    @Test
    fun `importing the same history twice writes it once and pays once`() =
        runBlocking {
            val history = ImportedHistory(
                workouts = listOf(benchWorkout(day = 1, weight = KG_100), benchWorkout(day = 3, weight = KG_110)),
            )
            import(history)
            val xp = totalXp()

            val again = import(history).report()

            assertEquals(0, again.workoutsImported)
            assertEquals(2, again.skipped.duplicateWorkouts)
            assertEquals(2, countRows("workout_session"))
            assertEquals(xp, totalXp())
        }

    @Test
    fun `a history older than the device's re-derives the device's records in the order they happened`() =
        runBlocking {
            import(ImportedHistory(workouts = listOf(benchWorkout(day = 20, weight = KG_100))))
            val xp = totalXp()

            val older = import(ImportedHistory(workouts = listOf(benchWorkout(day = 5, weight = KG_110)))).report()

            // Judged in order, day 5's 110 kg is the record and day 20's 100 kg beat nothing. Without
            // the re-derivation the older row would sit *below* the newer one as the latest of its
            // kind, and the records screen would show a 100 kg best for a lifter who has lifted 110.
            val heaviest = records.getRecordsForExercise(benchId).first().filter { it.type == PrType.MAX_WEIGHT }
            assertEquals(listOf(KG_110), heaviest.map { it.weight })
            assertEquals(5 * DAY, heaviest.single().achievedAt / DAY * DAY)

            // The session already on the device was replayed for its records only: its XP was
            // already claimed, so the total moved by exactly what the import reports.
            assertEquals(xp + older.xpEarned, totalXp())
            assertEquals(2, countRows("session_xp_claim"))
        }

    @Test
    fun `a cancelled import leaves nothing behind`() =
        runBlocking {
            val history = ImportedHistory(
                workouts = listOf(benchWorkout(day = 1, weight = KG_100), benchWorkout(day = 2, weight = KG_110)),
                bodyMeasurements = listOf(BodyMeasurement(weight = 80_000_000, createdAt = DAY, updatedAt = DAY)),
            )

            // Stop collecting once the first workout is in: the producer is cancelled inside the
            // transaction, and the whole import goes with it.
            importHistory(history).takeWhile { it !is HistoryImportProgress.Writing || it.workoutsWritten < 1 }.toList()

            assertEquals(0, countRows("workout_session"))
            assertEquals(0, countRows("body_measurement"))
            assertEquals(0, countRows("personal_record"))
            assertEquals(0, countRows("user_progress"))
        }

    // ------------------------------------------------------------------ refusals

    private fun parsing(result: () -> ParseResult) =
        object : HistoryParser {
            override fun parse(
                source: ImportSource,
                context: ImportContext,
            ): ParseResult = result()
        }

    private suspend fun importWith(parser: HistoryParser): HistoryImportProgress =
        importHistory(
            source = { "date,exercise".byteInputStream() },
            parser = parser,
            context = ImportContext(ZoneOffset.UTC, UnitSystem.METRIC),
        ).toList().last()

    @Test
    fun `a file the parser does not recognise is refused and writes nothing`() =
        runBlocking {
            assertEquals(
                HistoryImportProgress.Failed(HistoryImportError.NotRecognised),
                importWith(parsing { ParseResult.NotRecognised }),
            )
            assertEquals(0, countRows("workout_session"))
        }

    @Test
    fun `a parser that throws on a hostile file has it refused rather than crashing`() =
        runBlocking {
            val outcome = importWith(parsing { error("unexpected column") })

            assertEquals(HistoryImportProgress.Failed(HistoryImportError.Malformed("unexpected column")), outcome)
        }

    @Test
    fun `a parsed file goes through the same engine as a parsed history`() =
        runBlocking {
            val outcome = importWith(
                parsing { ParseResult.Parsed(ImportedHistory(workouts = listOf(benchWorkout(1, KG_100)))) },
            )

            assertEquals(1, (outcome as HistoryImportProgress.Complete).report.workoutsImported)
        }

    @Test
    fun `a file with nothing in it is reported as such`() =
        runBlocking {
            assertEquals(
                HistoryImportProgress.Failed(HistoryImportError.NothingToImport),
                importWith(parsing { ParseResult.Parsed(ImportedHistory()) }),
            )
        }

    private companion object {
        const val DAY = 86_400_000L
        const val NOW = 100 * DAY
        const val KG_100 = 100_000_000L
        const val KG_110 = 110_000_000L
    }
}
