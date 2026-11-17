package com.titan.core.domain.importer.jefit

import com.titan.core.domain.importer.ExerciseMatcher
import com.titan.core.domain.importer.ImportedExercise
import com.titan.core.domain.importer.SourceValues
import com.titan.core.domain.importer.SuffixedExerciseNames
import com.titan.core.domain.model.ExerciseType

/**
 * Which exercise a JEFIT row means.
 *
 * JEFIT identifies an exercise by its id (`eid`, `exercise_id`) and by which library the id is
 * in (`belongSys`: `1` for JEFIT's own, `0` for one the user created, whose id is its row in
 * CUSTOM EXERCISES). The name beside it is only what the exercise was called when the row was
 * written: JEFIT has renamed its exercises over the years ("Push Up" became "Push-Up"), and rows
 * from Apple Health or a watch carry no name at all. So two rows are one exercise when they share
 * an id and a library, whatever they are called, and only a row with no id is known by its name.
 */
internal data class JefitExerciseKey(private val id: String) {
    companion object {

        /** The exercise a row naming [exerciseId] in [system], called [name], means; null when it names none. */
        fun of(
            exerciseId: String?,
            system: String?,
            name: String?,
        ): JefitExerciseKey? =
            when {
                exerciseId != null -> JefitExerciseKey("${system.orEmpty()}#$exerciseId")
                name != null -> JefitExerciseKey("name#${ExerciseMatcher.normalised(name)}")
                else -> null
            }

        /** The key of the user's own exercise whose CUSTOM EXERCISES row has [id]. */
        fun custom(id: String?): JefitExerciseKey? = id?.let { of(it, CUSTOM_LIBRARY, null) }

        /** `belongSys` for an exercise the user created. */
        const val CUSTOM_LIBRARY = "0"
    }
}

/** One row of EXERCISE LOGS: one exercise of one workout, and every set of it, packed into [packed]. */
internal class JefitLog(row: JefitRow) {
    val id: String? = row[JefitColumn.ID]
    val key: JefitExerciseKey? = JefitExerciseKey.of(
        row[JefitColumn.EXERCISE_ID],
        row[JefitColumn.SYSTEM],
        row[JefitColumn.EXERCISE_NAME],
    )
    val name: String? = row[JefitColumn.EXERCISE_NAME]
    val date: String? = row[JefitColumn.DATE]
    val session: String? = row[JefitColumn.SESSION]
    val packed: String? = row[JefitColumn.LOGS]
    val intervals: String? = row[JefitColumn.INTERVALS]
}

/**
 * Every exercise a JEFIT export names, each built once from everything the export says about it,
 * so that every workout, template and library entry naming it hands the planner the same
 * [ImportedExercise].
 *
 * - **The name** is the user's own for an exercise they created (its CUSTOM EXERCISES row), and
 *   otherwise the one its most recent log gave it, then a routine's, then a note's. Cardio that
 *   JEFIT took from Apple Health or a watch arrives with no name anywhere, and the few of JEFIT's
 *   cardio exercises whose ids real exports name are named after them ([UNNAMED_CARDIO]). Any
 *   other exercise that no row names is passed on nameless, and the planner refuses its sets and
 *   counts them. Names go through [SuffixedExerciseNames] with [JEFIT_CATALOGUE_NAMES], reading the
 *   equipment from the front of the name, where JEFIT puts it.
 * - **The muscle** is JEFIT's body part, from the user's exercise or a routine's line, where it is
 *   one muscle group; see [BODY_PART_MUSCLES].
 * - **The type** is endurance for an exercise JEFIT files under Cardio, and otherwise left to the
 *   planner to infer from the sets.
 * - **The rest** is the timer a routine runs after it, and **the notes** the description the user
 *   gave an exercise they created.
 */
internal class JefitExercises(
    tables: JefitTables,
    logs: List<JefitLog>,
    routineLines: List<JefitRow>,
    notes: List<JefitRow>,
    cardioLogIds: Set<String>,
) {
    private val names = SuffixedExerciseNames(JEFIT_CATALOGUE_NAMES, leadingEquipment = true)
    private val custom = tables.rows(JefitSection.CUSTOM_EXERCISES, JefitColumn.ID, JefitColumn.NAME)
        .mapNotNull { row -> JefitExerciseKey.custom(row[JefitColumn.ID])?.let { it to row } }
        .toMap()
    private val routineByKey = routineLines.groupBy(::routineKey)

    /** Each exercise's name in its most recent log, a later log in the file winning a tie. */
    private val logNames: Map<JefitExerciseKey, String> = buildMap {
        logs.sortedBy { it.date.orEmpty() }.forEach { log ->
            val key = log.key
            val name = log.name
            if (key != null && name != null) put(key, name)
        }
    }
    private val noteNames = notes.mapNotNull { row ->
        val key = JefitExerciseKey.of(row[JefitColumn.EXERCISE_ID], row[JefitColumn.SYSTEM], null)
        row[JefitColumn.NOTE_TITLE]?.let { title -> key?.let { it to title } }
    }.toMap()
    private val cardioByLog = logs.filter { it.id in cardioLogIds }.mapNotNullTo(HashSet(), JefitLog::key)

    private val built = HashMap<JefitExerciseKey, ImportedExercise>()
    private val cardio = HashMap<JefitExerciseKey, Boolean>()
    private val assisted = HashMap<JefitExerciseKey, Boolean>()

    /** The exercise [key] means. */
    operator fun get(key: JefitExerciseKey): ImportedExercise = built.getOrPut(key) { build(key) }

    /** The exercises the user created, whether or not anything else names them. */
    val library: List<ImportedExercise>
        get() = custom.keys.map(::get)

    /**
     * Whether the export says [key] is logged as cardio: JEFIT files it under Cardio, or lists one
     * of its logs among its cardio logs. Every log of such an exercise packs its sets as cardio.
     */
    fun isCardio(key: JefitExerciseKey): Boolean =
        cardio.getOrPut(key) { bodyPart(key) == CARDIO_BODY_PART || key in cardioByLog }

    /**
     * Whether [key] is an assisted variation, whose weight is the counterweight a machine took
     * off rather than a load: Strong's spelling, "Pull Up (Assisted)", and JEFIT's own,
     * "Machine Assisted Pull-Up". See [SuffixedExerciseNames.namesAssistance].
     */
    fun isAssisted(key: JefitExerciseKey): Boolean =
        assisted.getOrPut(key) { SuffixedExerciseNames.namesAssistance(get(key).name) }

    /** The routine line [row]'s exercise. */
    fun routineKey(row: JefitRow): JefitExerciseKey? =
        JefitExerciseKey.of(
            row[JefitColumn.ROUTINE_EXERCISE_ID],
            row[JefitColumn.SYSTEM],
            row[JefitColumn.ROUTINE_EXERCISE_NAME],
        )

    private fun build(key: JefitExerciseKey): ImportedExercise {
        val lines = routineByKey[key].orEmpty()
        val name = custom[key]?.get(JefitColumn.NAME)
            ?: logNames[key]
            ?: lines.firstNotNullOfOrNull { it[JefitColumn.ROUTINE_EXERCISE_NAME] }
            ?: noteNames[key]
            ?: UNNAMED_CARDIO[key]
        val bodyPart = bodyPart(key)
        return names.exercise(name.orEmpty()).copy(
            type = ExerciseType.ENDURANCE.takeIf { bodyPart == CARDIO_BODY_PART },
            primaryMuscle = bodyPart?.let(BODY_PART_MUSCLES::get),
            notes = SourceValues.note(custom[key]?.get(JefitColumn.DESCRIPTION)),
            defaultRestSeconds = lines.firstNotNullOfOrNull { line -> line.whole(JefitColumn.REST)?.takeIf { it > 0 } },
        )
    }

    private fun bodyPart(key: JefitExerciseKey): Int? =
        (custom[key]?.let(::listOf).orEmpty() + routineByKey[key].orEmpty())
            .firstNotNullOfOrNull { it.whole(JefitColumn.BODY_PART) }
            ?.toInt()

    private companion object {

        /**
         * JEFIT's own cardio exercises that its health and watch imports log with no name, by
         * id, to the name the same id carries where JEFIT names it: in two real exports, 316 is
         * "Rowing", 317 "Running" and 321 "Treadmill Running". Other ids seen nameless (315,
         * 322, 1434) were never seen named, so they are not guessed at.
         */
        val UNNAMED_CARDIO: Map<JefitExerciseKey, String> = mapOf(
            "316" to "Rowing",
            "317" to "Running",
            "321" to "Treadmill Running",
        ).mapKeys { (id, _) -> checkNotNull(JefitExerciseKey.of(id, JEFIT_LIBRARY, null)) }

        /** `belongSys` for an exercise of JEFIT's own library. */
        const val JEFIT_LIBRARY = "1"

        /** JEFIT's body part for cardio exercises. */
        const val CARDIO_BODY_PART = 10

        /**
         * JEFIT's body parts that are one of Titan's muscle groups, to the group's
         * `muscle_group.name`.
         *
         * JEFIT numbers its eleven body parts in the order its exercise database lists them —
         * Abs, Back, Biceps, Chest, Forearms, Glutes, Shoulders, Triceps, Upper Legs, Lower Legs,
         * Cardio — and writes `11` for none. No document says so; it is the order every exercise
         * in two real exports agrees with (bench press 3, deadlift 1, hip thrust 5, hammer curl 4,
         * calf raises 9, treadmill 10). Back and Upper Legs are several muscles each, and Cardio
         * none, so they give no muscle rather than a guess; Lower Legs is the calves.
         */
        val BODY_PART_MUSCLES: Map<Int, String> = mapOf(
            0 to "ABDOMINALS",
            2 to "BICEPS",
            3 to "CHEST",
            4 to "FOREARMS",
            5 to "GLUTES",
            6 to "SHOULDERS",
            7 to "TRICEPS",
            9 to "CALVES",
        )
    }
}
