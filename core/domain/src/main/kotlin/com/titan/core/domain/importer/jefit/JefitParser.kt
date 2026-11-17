package com.titan.core.domain.importer.jefit

import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.importer.HistoryParser
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.csv.CsvReader
import com.titan.core.domain.importer.csv.CsvSections
import com.titan.core.domain.importer.csv.MalformedCsvException
import java.util.Locale

/**
 * Reads the account export JEFIT writes from its settings (Export Data): one CSV file holding
 * the account's tables, each section opened by a marker line such as
 * `### EXERCISE LOGS ######` and closed by a line of `#`, every table inside it under a header
 * row of its own. [CsvSections] reads that layout.
 *
 * ### What becomes what
 *
 * JEFIT publishes no specification. What follows is what real exports from 2024 and 2026 hold,
 * checked against independent importers written from other real exports.
 *
 * - **WORKOUT SESSIONS** and **EXERCISE LOGS** are the workouts: a session is a workout with a
 *   real start time and length, a log one exercise of it with its sets packed into one column.
 *   See [JefitWorkouts], and [JefitSetReader] for the packed sets, the per-set rows newer
 *   exports add (**EXERCISE SET LOGS**) and the cardio logs they list (**CARDIO EXERCISE LOGS**).
 * - **ROUTINES** are the templates, one per day of a routine; see [JefitRoutines].
 * - **PROFILE** is the body stats; see [JefitMeasurements].
 * - **CUSTOM EXERCISES** are the user's own exercises, imported whether or not they were trained;
 *   see [JefitExercises] for how every row's exercise is named, matched and described.
 * - **NOTES** are exercise notes, kept on the exercise of the workout they were written on.
 * - **SETTING** says the units every figure the user typed is in (`mass`, `length`), and the
 *   user's own units stand in where it does not; see [JefitUnits].
 *
 * ### What is deliberately not imported
 *
 * - **EXERCISE RECORDS.** Records are derived from the sets.
 * - **CARDIO LOGS.** In the exports seen, each of its rows repeats a cardio exercise log, and the
 *   rest are rows of nothing (strength exercises with every figure `0`), so importing it would
 *   count the cardio twice.
 * - **FAVORITE EXERCISES**, the rest of **SETTING**, a session's average heart rate, and a cardio
 *   set's calories, speed and laps: Titan has nowhere to put them.
 * - **Routine schedules and progression.** Titan has templates only.
 *
 * ### What a damaged file costs
 *
 * A file whose first line is not a section marker, or that has none of JEFIT's sections, is not
 * a JEFIT export and is [ParseResult.NotRecognised] — including every other app's CSV. Past that,
 * a damaged part costs that part: a table under a header without the columns its rows need is
 * left out, a row that cannot be placed is left out, and a set or a measurement that does not
 * read is passed on empty, so the planner refuses it and counts it in the report. Only a file
 * whose CSV breaks (an unclosed quote), whose sections hold rows under no header this parser
 * knows, or whose every exercise log is unplaceable with nothing else to import, is
 * [ParseResult.Malformed].
 */
class JefitParser : HistoryParser {

    override fun parse(
        source: ImportSource,
        context: ImportContext,
    ): ParseResult =
        source.open().use { stream ->
            try {
                val sections = CsvSections.read(CsvReader.open(stream, DELIMITER), ::isHeader)
                val jefit = sections?.sections?.any { it.name.uppercase(Locale.ROOT) in JefitSection.ALL } == true
                if (sections == null || !jefit) ParseResult.NotRecognised else read(JefitTables(sections), context)
            } catch (failure: MalformedCsvException) {
                ParseResult.Malformed(failure.message.orEmpty())
            }
        }

    private fun read(
        tables: JefitTables,
        context: ImportContext,
    ): ParseResult {
        val units = JefitUnits.of(tables, context)
        val logs = tables.rows(
            JefitSection.EXERCISE_LOGS,
            JefitColumn.LOGS,
        ).mapIndexed { index, row -> JefitLog(row, index) }
        val routines = JefitRoutines(tables)
        val notes = tables.rows(JefitSection.NOTES, JefitColumn.NOTE)
        val cardioLogIds = tables.rows(JefitSection.CARDIO_EXERCISE_LOGS, JefitColumn.CARDIO_LOG_ID)
            .mapNotNullTo(HashSet()) { it[JefitColumn.CARDIO_LOG_ID] }
        val exercises = JefitExercises(tables, logs, routines.lines, notes, cardioLogIds)
        val reader = JefitWorkouts(tables, context.zone, exercises, JefitSetReader(units), routines.dayNames, notes)

        // The workouts first: a routine plans an exercise in time or in reps by how it was logged.
        val workouts = reader.read(logs)
        val history = ImportedHistory(
            exercises = exercises.library,
            workouts = workouts,
            templates = routines.templates(exercises) { key -> exercises.isCardio(key) || reader.isTimed(key) },
            bodyMeasurements = JefitMeasurements(tables, units, context.zone).read(),
        )
        return when {
            tables.unreadable -> ParseResult.Malformed(
                "no section of the export has a header with the columns JEFIT writes",
            )

            history.isEmpty && logs.isNotEmpty() && reader.placed == 0 ->
                ParseResult.Malformed(
                    "none of the export's ${logs.size} exercise logs has a workout or a date that can be read",
                )

            else -> ParseResult.Parsed(history)
        }
    }

    private companion object {

        /** JEFIT writes commas whatever the phone's locale; the first line, a marker, has none to sniff. */
        const val DELIMITER = ','

        private val COLUMN_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /**
         * Whether [record] is a header row: two or more fields, each a column name. JEFIT's
         * columns are its database's (`row_id`, `USERID`, `belongSys`), and every one of its rows
         * holds an id, a number no header does, so a row is never mistaken for one.
         */
        fun isHeader(record: List<String>): Boolean = record.size > 1 && record.all { COLUMN_NAME.matches(it.trim()) }
    }
}
