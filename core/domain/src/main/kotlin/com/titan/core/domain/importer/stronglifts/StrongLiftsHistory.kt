package com.titan.core.domain.importer.stronglifts

import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportedExercise
import com.titan.core.domain.importer.ImportedHistory
import com.titan.core.domain.importer.ImportedSet
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImportedWorkoutExercise
import com.titan.core.domain.importer.ParseResult
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SourceWeightUnit
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.importer.WeightFigure
import com.titan.core.domain.model.BodyMeasurement
import com.titan.core.domain.model.EquipmentType
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * What both layouts build as they read their rows: the workouts by start, the exercises by name and
 * a body weight a day, each as the parser's class doc describes.
 */
internal class StrongLiftsHistory(private val context: ImportContext) {

    /** The unit a weight is in when nothing in the file says. */
    val userUnit: SourceWeightUnit = SourceWeightUnit.of(context.unitSystem)

    private val names = SuffixedExerciseNames(STRONGLIFTS_CATALOGUE_NAMES, leadingEquipment = true)
    private val barbellLifts = STRONGLIFTS_BARBELL_LIFTS.mapTo(HashSet(), ExerciseMatcher::normalised)
    private val exercises = LinkedHashMap<String, ImportedExercise>()
    private val workouts = LinkedHashMap<Long, WorkoutDraft>()
    private val bodyWeights = LinkedHashMap<LocalDate, BodyMeasurement>()

    /** The exercise StrongLifts calls [name]: one per name, whether or not a set of it was performed. */
    fun exercise(name: String): ImportedExercise =
        exercises.getOrPut(name) {
            val exercise = names.exercise(name)
            val barbell = exercise.equipment == null && ExerciseMatcher.normalised(name) in barbellLifts
            if (barbell) exercise.copy(equipment = EquipmentType.BARBELL) else exercise
        }

    /**
     * The workout that started at [startedAt], begun with [name] and [durationSeconds] when this is
     * its first row; a later row of it does not change either, since every row repeats them.
     */
    fun workout(
        startedAt: Long,
        name: String?,
        durationSeconds: Long?,
    ): WorkoutDraft = workouts.getOrPut(startedAt) { WorkoutDraft(startedAt, name, durationSeconds) }

    /**
     * [text], the body weight on the row of a workout that started at [takenAt], as the measurement
     * of that day taken then, in the unit the cell names or else [unit]. Only the first row of a day
     * counts, as StrongLifts keeps one weight a day. A figure that is not a number is passed on as
     * an entry with nothing in it, which the planner refuses and counts.
     */
    fun bodyWeight(
        takenAt: Long,
        text: String?,
        unit: SourceWeightUnit,
    ) {
        val day = Instant.ofEpochMilli(takenAt).atZone(context.zone).toLocalDate()
        if (text == null || day in bodyWeights) return
        val figure = WeightFigure.parse(text)
        if (figure?.value == 0.0) return
        bodyWeights[day] = BodyMeasurement(
            weight = figure?.milligrams(unit),
            createdAt = takenAt,
            updatedAt = takenAt,
        )
    }

    /**
     * The history read, or [ParseResult.Malformed] when the export had [rows] rows and none of them
     * had a date and an exercise that could be read: [readRows] counts those that did.
     */
    fun result(
        rows: Int,
        readRows: Int,
    ): ParseResult =
        if (readRows == 0 && rows > 0) {
            ParseResult.Malformed("none of the export's $rows rows has a date and an exercise that can be read")
        } else {
            ParseResult.Parsed(
                ImportedHistory(
                    exercises = exercises.values.toList(),
                    workouts = workouts.values.mapNotNull(WorkoutDraft::toWorkout),
                    bodyMeasurements = bodyWeights.values.toList(),
                ),
            )
        }

    companion object {

        /**
         * The set a `Set N` cell pair describes: [count], the reps or, when [timed], the seconds, and
         * [load], the weight in [unit]. Null when the set was not performed — a blank, or the `0`
         * StrongLifts writes for a set the user skipped or failed outright — and a set with nothing
         * recorded when a figure is not a number or the count is negative, which the planner
         * refuses and counts where dropping it here would lose it without a word.
         */
        fun performedSet(
            count: String?,
            load: String?,
            unit: SourceWeightUnit,
            timed: Boolean,
        ): ImportedSet? {
            if (count == null) return null
            val figure = if (timed) SourceValues.elapsedSeconds(count)?.toDouble() else SourceValues.decimal(count)
            val weight = SourceValues.decimal(load)
            return when {
                figure == 0.0 -> null

                figure == null || figure < 0.0 || (load != null && weight == null) -> ImportedSet()

                else -> ImportedSet(
                    reps = figure.roundToInt().takeUnless { timed },
                    duration = figure.toLong().takeIf { timed },
                    weight = weight?.let(unit::toMilligrams),
                )
            }
        }
    }
}

/** One workout as its rows are read. */
internal class WorkoutDraft(
    private val startedAt: Long,
    private val name: String?,
    private val durationSeconds: Long?,
) {
    private val lines = mutableListOf<Line>()
    private val notes = LinkedHashSet<String>()

    /** One exercise of the workout, with the sets performed of it and the note its row carried. */
    fun line(
        exercise: ImportedExercise,
        sets: List<ImportedSet>,
        rowNote: String?,
    ) {
        lines += Line(exercise, sets, rowNote)
    }

    /** A note on the workout itself, kept once however many rows carry it. */
    fun note(text: String?) {
        text?.let(notes::add)
    }

    /**
     * The workout, or null when not one set of it was performed: StrongLifts exports the exercises
     * it planned, and a workout of skipped sets is no workout.
     *
     * A note every row carries is the workout's, as the newer layout repeats it, and the rows' notes
     * stay on their exercises when they differ.
     */
    fun toWorkout(): ImportedWorkout? {
        val shared = lines.map(Line::note).distinct().singleOrNull()
        val performed = lines.filter { it.sets.isNotEmpty() }
        if (performed.isEmpty()) return null
        return ImportedWorkout(
            startedAt = startedAt,
            durationSeconds = durationSeconds,
            name = name,
            notes = (notes + listOfNotNull(shared)).joinToString("\n").ifEmpty { null },
            exercises = performed.map { line ->
                ImportedWorkoutExercise(
                    exercise = line.exercise,
                    sets = line.sets,
                    notes = line.note.takeIf { shared == null },
                )
            },
        )
    }

    private class Line(
        val exercise: ImportedExercise,
        val sets: List<ImportedSet>,
        val note: String?,
    )
}
