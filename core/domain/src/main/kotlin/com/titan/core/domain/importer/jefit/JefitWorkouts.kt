package com.titan.core.domain.importer.jefit

import com.titan.core.domain.importer.ImportContext
import com.titan.core.domain.importer.ImportPlanner
import com.titan.core.domain.importer.ImportedWorkout
import com.titan.core.domain.importer.ImportedWorkoutExercise
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.checkSetCount

/**
 * Turns EXERCISE LOGS into workouts, placing each log in the WORKOUT SESSIONS row it names.
 *
 * - **A session** is one workout: `starttime` and `endtime` in seconds since 1970, `total_time`
 *   its length in seconds, `calories` what it burned, and `day_id` the routine day it was
 *   started from, whose name becomes the workout's.
 * - **A log** is one exercise of its session (`belongsession`), in file order, which is the
 *   order JEFIT logged them in. An exercise done twice in one session is two logs, and two
 *   exercises of the workout, as JEFIT counts it.
 * - **A log whose session is not in the file, or has no start**, goes into the workout of its
 *   day (`mydate`), starting at noon in the user's zone, as [SourceValues.localDateAtNoon]
 *   explains. Older exports place none of their logs in a session.
 * - **An exercise note** from NOTES is the note of the first log of its exercise on its date.
 *
 * A session without a log recorded nothing — JEFIT keeps one for a workout opened and closed
 * again — and is left out, as is a log with no exercise, a log that is in neither a session nor
 * a day that reads, and a log whose sets record nothing.
 */
internal class JefitWorkouts(
    tables: JefitTables,
    private val context: ImportContext,
    private val exercises: JefitExercises,
    private val sets: JefitSetReader,
    private val dayNames: Map<String, String>,
    notes: List<JefitRow>,
) {
    private val sessions: Map<String?, Session> = tables.rows(JefitSection.WORKOUT_SESSIONS, JefitColumn.ID)
        .map(::Session)
        .associateBy(Session::id)

    private val setRows: Map<String?, List<JefitRow>> = tables.rows(JefitSection.EXERCISE_SET_LOGS, JefitColumn.LOG_ID)
        .groupBy { it[JefitColumn.LOG_ID] }
        .mapValues { (_, rows) -> rows.sortedByOnce { it.whole(JefitColumn.SET_INDEX) ?: Long.MAX_VALUE } }

    private val notes: MutableMap<Pair<JefitExerciseKey, String?>, String> = notes
        .mapNotNull { row ->
            val key = JefitExerciseKey.of(row[JefitColumn.EXERCISE_ID], row[JefitColumn.SYSTEM], null)
            val text = SourceValues.note(row[JefitColumn.NOTE])
            if (key == null || text == null) null else (key to row[JefitColumn.DATE]) to text
        }
        .groupBy({ it.first }, { it.second })
        .mapValuesTo(HashMap()) { (_, texts) -> texts.joinToString("\n") }

    /** How many logs the last [read] found a workout for, whatever their sets recorded. */
    var placed = 0
        private set

    /** The shapes of the sets [read] found of each exercise. */
    private val shapes = HashMap<JefitExerciseKey, ImportPlanner.ShapeTally>()

    /**
     * Whether the sets [read] found of [key] are mostly timed rather than counted: a hold or a
     * piece of cardio. The planner judges an exercise's type with the same tally, so a routine
     * plans such an exercise in time rather than in reps; see [JefitRoutines].
     */
    fun isTimed(key: JefitExerciseKey): Boolean = shapes[key]?.isTimed ?: false

    /**
     * The workouts [logs] make, in the order their first log appears.
     *
     * @throws com.titan.core.domain.importer.TooManySetsException once the sets read pass
     *   [ImportContext.maxSets].
     */
    fun read(logs: List<JefitLog>): List<ImportedWorkout> {
        val drafts = LinkedHashMap<String, WorkoutDraft>()
        var setCount = 0
        logs.forEach { log ->
            val key = log.key ?: return@forEach
            val draft = draftFor(log, drafts) ?: return@forEach
            placed++
            val exercise = exercises[key]
            val performed = sets.sets(
                log,
                setRows[log.id].orEmpty(),
                exercises.isCardio(key),
                exercises.isAssisted(key),
            )
            if (performed.isNotEmpty()) {
                setCount += performed.size
                context.checkSetCount(setCount)
                draft.exercises += ImportedWorkoutExercise(exercise, performed, notes = notes.remove(key to log.date))
                val shape = shapes.getOrPut(key, ImportPlanner::ShapeTally)
                performed.forEach { set -> shape.add(set.reps, set.duration, set.distance) }
            }
        }
        return drafts.values.filter { it.exercises.isNotEmpty() }.map(WorkoutDraft::toWorkout)
    }

    /** The workout [log] belongs to: its session's, or its day's when the session cannot place it. */
    private fun draftFor(
        log: JefitLog,
        drafts: MutableMap<String, WorkoutDraft>,
    ): WorkoutDraft? {
        val session = log.session?.let(sessions::get)
        val startedAt = session?.startedAt
        if (session != null && startedAt != null) {
            return drafts.getOrPut("session#${session.id}") {
                WorkoutDraft(startedAt, session.durationSeconds, session.dayId?.let(dayNames::get), session.calories)
            }
        }
        val noon = SourceValues.localDateAtNoon(log.date, context.zone) ?: return null
        return drafts.getOrPut("day#$noon") { WorkoutDraft(noon) }
    }

    /** One row of WORKOUT SESSIONS. */
    private class Session(row: JefitRow) {
        val id: String? = row[JefitColumn.ID]
        val startedAt: Long? =
            SourceValues.epochSeconds(
                row[JefitColumn.START],
            ) ?: SourceValues.epochSeconds(row[JefitColumn.START_MISSPELT])
        val durationSeconds: Long? = row.whole(JefitColumn.TOTAL_TIME)?.takeIf { it > 0 }
            ?: SourceValues.epochSeconds(row[JefitColumn.END])
                ?.let { end -> startedAt?.let { (end - it) / MILLIS_PER_SECOND } }
                ?.takeIf { it > 0 }
        val dayId: String? = row[JefitColumn.DAY_ID]
        val calories: Int? = row.whole(
            JefitColumn.CALORIES,
        )?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    }

    private class WorkoutDraft(
        val startedAt: Long,
        val durationSeconds: Long? = null,
        val name: String? = null,
        val calories: Int? = null,
    ) {
        val exercises = mutableListOf<ImportedWorkoutExercise>()

        fun toWorkout() =
            ImportedWorkout(
                startedAt = startedAt,
                durationSeconds = durationSeconds,
                name = name,
                exercises = exercises.toList(),
                calories = calories,
            )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
