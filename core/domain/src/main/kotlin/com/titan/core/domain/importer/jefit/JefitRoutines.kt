package com.titan.core.domain.importer.jefit

import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.ImportedTemplate
import com.titan.core.domain.importer.ImportedTemplateExercise
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.model.PlannedSet
import kotlin.math.roundToInt

/**
 * Turns the ROUTINES section into templates.
 *
 * ROUTINES holds three kinds of row, each under a header of its own: a routine (`_id`, `name`),
 * the days of a routine (`package` is the routine's `_id`), and the exercises of a day
 * (`belongplan` is the day's `_id`). JEFIT plans a week of days; Titan has templates, so each day
 * with an exercise becomes one, named after its routine and the day — "Push Pull Legs - Push" —
 * or after the routine alone when it has only that day.
 *
 * Each exercise of a day plans `setcount` sets, in `mysort` order. A lift plans the reps
 * `targetrep` sets out: one figure for every set (`8`), as older exports write it, or one a set
 * (`12,10,8`), as newer ones do. A timed exercise — cardio, or one whose logged sets are mostly
 * timed, such as a plank — plans the time JEFIT's interval timer runs (`interval_time`, in
 * `interval_unit`) instead, because JEFIT keeps a `targetrep` on it that means nothing, and a
 * plan in reps would have the planner take the exercise for a lift and refuse every timed set
 * logged of it. Exercises sharing a `superset` value other than `0` are one superset. `timer`,
 * the rest after the exercise, is the exercise's rest; see [JefitExercises].
 *
 * The weights in a line's `logs` are not planned: they are the last performance, which JEFIT
 * copies into the next session, and Titan shows the previous performance beside every set itself.
 */
internal class JefitRoutines(tables: JefitTables) {

    private val routines = tables.rows(JefitSection.ROUTINES, JefitColumn.ID, JefitColumn.NAME)
        .filterNot { it.has(JefitColumn.PACKAGE) || it.has(JefitColumn.DAY) }
    private val days = tables.rows(JefitSection.ROUTINES, JefitColumn.PACKAGE, JefitColumn.ID, JefitColumn.NAME)

    /** The exercises of every day, in file order. */
    val lines: List<JefitRow> = tables.rows(JefitSection.ROUTINES, JefitColumn.DAY)

    /** Every day's name, by its `_id`, which is what a workout session's `day_id` names. */
    val dayNames: Map<String, String> = buildMap {
        days.forEach { day ->
            val id = day[JefitColumn.ID]
            val name = day[JefitColumn.NAME]
            if (id != null && name != null) put(id, name)
        }
    }

    /**
     * The templates the routines' days make, with [exercises] resolving each line's exercise and
     * [isTimed] saying which exercises are planned in time rather than in reps.
     */
    fun templates(
        exercises: JefitExercises,
        isTimed: (JefitExerciseKey) -> Boolean,
    ): List<ImportedTemplate> {
        val routineNames = routines.associate { it[JefitColumn.ID] to it[JefitColumn.NAME] }
        val daysPerRoutine = days.groupingBy { it[JefitColumn.PACKAGE] }.eachCount()
        val linesByDay = lines.withIndex()
            .sortedWith(compareBy({ it.value.whole(JefitColumn.SORT) ?: Long.MAX_VALUE }, { it.index }))
            .map { it.value }
            .groupBy { it[JefitColumn.DAY] }

        return days.mapNotNull { day ->
            val dayLines = linesByDay[day[JefitColumn.ID]].orEmpty()
            if (dayLines.isEmpty()) return@mapNotNull null
            val routine = day[JefitColumn.PACKAGE]
            val groups = HashMap<String, Int>()
            ImportedTemplate(
                name = name(routineNames[routine], day[JefitColumn.NAME], (daysPerRoutine[routine] ?: 0) > 1),
                exercises = dayLines.mapNotNull { line ->
                    exercises.routineKey(line)?.let { key ->
                        ImportedTemplateExercise(
                            exercise = exercises[key],
                            sets = plannedSets(line, isTimed(key)),
                            supersetGroup = line[JefitColumn.SUPERSET]
                                ?.takeUnless { it == NO_SUPERSET }
                                ?.let { groups.getOrPut(it) { groups.size } },
                        )
                    }
                },
            )
        }
    }

    /** "Routine - Day", or the routine's name alone when it has [severalDays] false. */
    private fun name(
        routine: String?,
        day: String?,
        severalDays: Boolean,
    ): String =
        when {
            routine == null -> day.orEmpty()
            day == null || !severalDays -> routine
            else -> "$routine - $day"
        }

    private fun plannedSets(
        line: JefitRow,
        timed: Boolean,
    ): List<PlannedSet> {
        val reps = line[JefitColumn.TARGET_REPS]?.split(',')
            ?.take(MAX_PLANNED_SETS)
            ?.map { SourceValues.decimal(it)?.roundToInt()?.takeIf { reps -> reps > 0 } }
            .orEmpty()
        val count = line.whole(JefitColumn.SET_COUNT)?.takeIf { it in 1..MAX_PLANNED_SETS }?.toInt() ?: reps.size
        val duration = plannedSeconds(line).takeIf { timed }
        return List(count) { index ->
            PlannedSet(reps = (reps.getOrNull(index) ?: reps.lastOrNull()).takeUnless { timed }, duration = duration)
        }
    }

    /** The interval timer [line] runs each set against, in seconds, or null when it runs none. */
    private fun plannedSeconds(line: JefitRow): Long? {
        val enabled = line[JefitColumn.INTERVAL_ENABLED]?.let { it != DISABLED } ?: true
        val time = line.whole(JefitColumn.INTERVAL_TIME)?.takeIf { enabled && it > 0 } ?: return null
        val unit = line[JefitColumn.INTERVAL_UNIT]?.let(ExerciseMatcher::normalised)
        return when (unit) {
            null, in SECONDS -> time
            in MINUTES -> time * SECONDS_PER_MINUTE
            else -> null
        }
    }

    private companion object {

        /**
         * No plan has a hundred sets of one exercise. A count past that is a damaged row, and
         * planning it would build a list as long as the row claims.
         */
        const val MAX_PLANNED_SETS = 100

        const val NO_SUPERSET = "0"
        const val DISABLED = "0"
        const val SECONDS_PER_MINUTE = 60L

        val SECONDS = setOf("sec", "secs", "s", "second", "seconds")
        val MINUTES = setOf("min", "mins", "m", "minute", "minutes")
    }
}
