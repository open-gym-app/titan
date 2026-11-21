package com.titan.core.domain.importer.stronglifts

import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.importer.DatedRows
import com.titan.core.domain.importer.HistoryParser
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.importer.csv.CsvReader
import com.titan.core.domain.importer.readInTwoPasses

/**
 * Reads the workout history StrongLifts exports: Settings › Export Data in the app, which writes
 * one CSV file of every workout, and the same file mailed to the user through Contact Support when
 * the export is behind the paywall.
 *
 * ### The formats
 *
 * StrongLifts publishes no specification beyond where the button is, so what follows is what real
 * exports contain. The app has written two layouts, and both are read, every column by name:
 *
 * - **One row per exercise**, the app's export today: `Date (yyyy/mm/dd), Workout, Workout Name,
 *   Program Name, Body Weight (KG), Exercise, Sets×Reps, Sets×Time, Top Set (Reps×KG), e1RM (KG),
 *   Reps, Volume (KG), Workout Volume (KG), Duration (hours), Start Time (h:mm), End Time (h:mm),
 *   Notes`, then a `Set N (Reps)` and a `Set N (KG)` column for each set the longest exercise had.
 *   The units in the headers are the user's, `KG`, `LB` or `LBS`. Every row repeats its workout's
 *   date, number, name, body weight, length, start, end and notes. Some exports spell the
 *   summaries `SetsxReps` and `Top Set Reps x KG`, quote every cell, or start a weight column's
 *   name with a space, and some regions get semicolons and decimal commas; none of that is read
 *   differently. A set StrongLifts planned and the user skipped or failed outright has `0` reps.
 * - **One row per workout**, the app's earlier export (files from 2015 and 2017): `Date, Note,
 *   Workout, Body Weight`, then for each exercise a name column (`Exercise 1`), `Weight (KG)`,
 *   `Weight (LB)` and `Set 1` to `Set 5`. The weight is the exercise's, every set at it, written
 *   in both units: the one the user trained in as they logged it, the other rounded to a plate.
 *   The body weight carries the user's unit in the cell (`64.5kg`, `95.5KG`), so it is what says
 *   which of the two weights to read; with no unit there, the user's own setting does. The header names the program's
 *   three exercises, and rows with assistance work run on past it in groups of the same shape,
 *   which are read as if the header had named them. The date is written as the phone writes one:
 *   `02/05/17` and `06/02/2015`, day first, in the exports seen, and month first is read as well.
 *
 * A date with its year last is read in the order the file proves, [SourceValues.dayMonthOrder]: a
 * history long enough to reach a thirteenth of the month says which of the two numbers is the day.
 * A file that never says, or says both, has those dates unread rather than guessed.
 *
 * ### What becomes what
 *
 * A workout is the rows that share a start: the date at the start time when the export has one,
 * and noon that day ([SourceValues.localDateAtNoon]) when it does not. Every row of a workout
 * carries both, and two workouts the same day start at different times, so a re-import is a
 * duplicate of every workout. `Workout`, the running number in the newer layout, is not needed for
 * that. Two workouts that share a start, as two rows of the older layout on one day do, are one
 * workout; apart, the second would be refused as a duplicate of the first and its sets lost.
 *
 * - **The name** is `Workout Name` (`Workout A`), or the older layout's `Workout` letter written
 *   the way the newer layout writes it.
 * - **The length** is the end time less the start time, to the minute, unless `Duration (hours)`
 *   agrees with it to within a minute: then it is that figure, which has seconds in it. Exports
 *   write the figure to seven decimals or to one, so on its own it is sometimes the less precise of
 *   the two.
 * - **The notes.** The newer layout writes a workout's note on every row of the workout, so a note
 *   every row carries is the workout's, and one only some rows carry is the exercise's. The older
 *   layout's `Note` is the workout's.
 * - **Sets.** Each `Set N` with reps is a set: the reps, and the weight beside it, in the unit the
 *   header names. Fewer reps than planned is a failed set, which is still a set the user performed,
 *   and is imported with the reps done. A blank or `0` is a set that was not performed, and is
 *   not history. An exercise the newer layout times (a `Sets×Time` summary and no `Sets×Reps`)
 *   has its seconds in the reps column, and they are read as the set's time.
 * - **Assisted work** is written as a negative weight (`-5` on a chin-up), which the planner reads
 *   as not recorded, so the reps are the set; a positive weight on a chin-up or a dip is the load
 *   added to the user's body, and is kept.
 * - **Body weight** becomes a body measurement, one a day, as StrongLifts keeps it, taken when the
 *   first workout of that day started. `0` is a day the user did not weigh in.
 * - **Exercises** go through [SuffixedExerciseNames] with [STRONGLIFTS_CATALOGUE_NAMES] and the
 *   equipment read from the front of the name; the program's barbell lifts, which do not name the
 *   bar, are barbell lifts all the same ([STRONGLIFTS_BARBELL_LIFTS]). Every exercise a row names
 *   is imported, including one whose every set was skipped. The type is left to the planner to
 *   infer from the sets.
 *
 * ### What is deliberately not imported
 *
 * - **The program**: `Program Name`, the A/B schedule, the next workout's weights and the deload
 *   rules. Titan has templates, not programs, and the export holds no plan, only what was done.
 * - **StrongLifts' own figures**: `Sets×Reps`, `Top Set`, `e1RM`, `Reps`, `Volume` and `Workout
 *   Volume`. Titan derives its own from the sets, and records are re-derived.
 * - **Warm-ups**, which the export does not hold.
 * - **A heading in another language.** The headers are read in English; an export whose headers
 *   StrongLifts translated is not recognised.
 *
 * ### What a damaged file costs
 *
 * A header without a date column, `Exercise` and a `Set N (Reps)` column, or without `Date`,
 * `Workout`, an `Exercise N` column and a weight column after it, is not a StrongLifts export at
 * all, and is [ParseResult.NotRecognised]; `Set N (Reps)` and `Exercise N` are what no other app
 * writes. A weight, body weight or duration column whose header names something that is not a
 * unit is [ParseResult.Malformed], because no figure under it could be read. Past that, one bad
 * row is skipped rather than costing the history: a row whose date does not read or that names no
 * exercise is left out, and a set whose reps or weight is not a number is passed on with nothing
 * recorded, so the planner refuses it and counts it in the report. A body weight that is not a
 * number is passed on the same way. Only a file whose every row is unreadable, or whose CSV breaks
 * in a way no row can be recovered from, is [ParseResult.Malformed].
 */
class StrongLiftsParser : HistoryParser {

    override fun parse(
        source: ImportSource,
        context: ImportContext,
    ): ParseResult =
        source.open().use { stream ->
            val reader = CsvReader.open(stream)
            val layout = reader.readHeader()?.let { header ->
                ExerciseRows.of(header, context) ?: WorkoutRows.of(header, context)
            }
            val problem = layout?.problem
            when {
                layout == null -> ParseResult.NotRecognised
                problem != null -> ParseResult.Malformed(problem)
                else -> readInTwoPasses(source, reader, layout, context)
            }
        }
}

/**
 * One of the two layouts a StrongLifts export comes in, as its header describes it. Its dates may
 * be written year last, so its rows are read in two passes; see [readInTwoPasses].
 */
internal sealed interface StrongLiftsLayout : DatedRows {

    /** Why no figure in one of the columns can be read, its header naming no unit Titan knows; null when none. */
    val problem: String?
}
