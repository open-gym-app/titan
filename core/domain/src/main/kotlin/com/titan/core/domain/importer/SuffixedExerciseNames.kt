package com.titan.core.domain.importer

import com.titan.core.domain.model.EquipmentType

/**
 * Turns exercise names that carry their equipment in a trailing parenthesis — Strong's
 * "Bench Press (Barbell)", a convention Hevy's library shares — into [ImportedExercise]s.
 *
 * The parenthesis is the most useful thing in such a name, because it is what tells two rows of
 * the catalogue apart: "Bench Press (Dumbbell)" is not the barbell lift, and an import that
 * matched it there would merge the two lifts' records. So the equipment it names becomes
 * [ImportedExercise.equipment], which [ExerciseMatcher] holds every match to, and the name is
 * kept whole, parenthesis included, as the name a custom exercise is created under — it is the
 * name the user knows the exercise by.
 *
 * ### Which catalogue exercise it is
 *
 * [ImportedExercise.catalogueName] comes from two places, in order:
 *
 * 1. **The source's own table**, [catalogueNames], for the names no rule could translate:
 *    "Bench Press (Barbell)" is the catalogue's "Barbell Bench Press - Medium Grip". Every entry
 *    is a decision somebody checked, because a wrong one files a lift under another movement.
 * 2. **The name before the parenthesis**, when the parenthesis names equipment Titan knows:
 *    "Romanian Deadlift (Barbell)" is the catalogue's "Romanian Deadlift" when that row is a
 *    barbell exercise, and only then — the matcher refuses a row whose equipment differs, so
 *    "Preacher Curl (Dumbbell)" does not become the catalogue's barbell preacher curl. A
 *    parenthesis that is not equipment ("Pull Up (Assisted)", "Push Up (Knees)") names a
 *    variation, and dropping it would merge the variation into the plain movement, so it
 *    gets no such reading.
 *
 * Whatever neither finds is left to the matcher's own name comparison and, failing that, created
 * as a custom exercise — the safe outcome, which the user can see and rename.
 *
 * ### Equipment at the front of the name
 *
 * Some libraries put the equipment first instead: JEFIT's "Barbell Bench Press", "Cable Seated
 * Row", "Machine Leg Extension". With [leadingEquipment], a name with no equipment in a trailing
 * parenthesis has it read from its first words, so a custom exercise is created with it and the
 * matcher holds a match to it, as it does for a parenthesis. The name is not shortened for it:
 * "Barbell Squat" without its first word is a different catalogue row's name ("Squat" on any
 * equipment) far more often than it is a better match, and the source's table already maps the
 * names that need it. Off by default, because a source that writes plain words ("Cable Face Pull"
 * in FitNotes) is read as it always was.
 *
 * @param catalogueNames a source name, as the source writes it, to the name of the catalogue
 *   exercise it is known to be. Looked up by [ExerciseMatcher.normalised] name, so case and
 *   punctuation in the source do not matter.
 * @param leadingEquipment whether the first words of a name may say its equipment; see above.
 */
class SuffixedExerciseNames(
    catalogueNames: Map<String, String>,
    private val leadingEquipment: Boolean = false,
) {

    private val aliases: Map<String, String> = catalogueNames.mapKeys { (name, _) -> ExerciseMatcher.normalised(name) }

    /** The exercise the source calls [name]. */
    fun exercise(name: String): ImportedExercise {
        val trimmed = name.trim()
        val (base, label) = split(trimmed)
        val equipment = label?.let(::equipmentOf)
        return ImportedExercise(
            name = trimmed,
            catalogueName = aliases[ExerciseMatcher.normalised(trimmed)] ?: base.takeIf { equipment != null },
            equipment = equipment ?: leadingEquipmentOf(trimmed).takeIf { leadingEquipment },
        )
    }

    companion object {

        private val SUFFIXED = Regex("(.*\\S)\\s*\\(([^()]*)\\)")

        /**
         * Equipment names as these sources write them, by [ExerciseMatcher.normalised] label.
         *
         * A Smith machine — Hevy also writes it `(Smith)` — and a plate-loaded machine are
         * [EquipmentType.MACHINE] because that is where the seed catalogue files them (its Smith
         * machine rows all are), and a treadmill because the catalogue's treadmill runs and
         * walks are machine rows too.
         */
        private val EQUIPMENT = mapOf(
            "barbell" to EquipmentType.BARBELL,
            "dumbbell" to EquipmentType.DUMBBELL,
            "dumbbells" to EquipmentType.DUMBBELL,
            "machine" to EquipmentType.MACHINE,
            "smith machine" to EquipmentType.MACHINE,
            "smith" to EquipmentType.MACHINE,
            "plate loaded" to EquipmentType.MACHINE,
            "treadmill" to EquipmentType.MACHINE,
            "cable" to EquipmentType.CABLE,
            "kettlebell" to EquipmentType.KETTLEBELL,
            "band" to EquipmentType.BAND,
            "resistance band" to EquipmentType.BAND,
            "bodyweight" to EquipmentType.BODYWEIGHT,
            "ez bar" to EquipmentType.EZ_CURL_BAR,
            "ez curl bar" to EquipmentType.EZ_CURL_BAR,
            "medicine ball" to EquipmentType.MEDICINE_BALL,
            "stability ball" to EquipmentType.EXERCISE_BALL,
            "swiss ball" to EquipmentType.EXERCISE_BALL,
            "exercise ball" to EquipmentType.EXERCISE_BALL,
            "foam roller" to EquipmentType.FOAM_ROLL,
            "foam roll" to EquipmentType.FOAM_ROLL,
        )

        /**
         * [name] as what comes before a trailing parenthesis and what is inside it — `("Bench
         * Press", "Barbell")` — or `(name, null)` when it does not end in one.
         */
        fun split(name: String): Pair<String, String?> {
            val match = SUFFIXED.matchEntire(name.trim()) ?: return name.trim() to null
            return match.groupValues[1] to match.groupValues[2].trim()
        }

        /**
         * The equipment [label] names, or null when it names none Titan has a value for, or is
         * not equipment at all ("Assisted", "Single Arm"). An attachment after a dash is the
         * equipment's detail, not another piece of kit: "Cable - Straight Bar" is a cable.
         */
        fun equipmentOf(label: String): EquipmentType? =
            EQUIPMENT[ExerciseMatcher.normalised(label.substringBefore(" - "))]

        /**
         * The equipment the first words of [name] name — "Barbell" in "Barbell Bench Press",
         * "Smith Machine" in "Smith Machine Squat" — or null when they name none, or are the
         * whole name. The longest name of a piece of equipment wins.
         *
         * A band, bodyweight or a bare "Smith" at the front is not read. The catalogue files rows
         * that start that way under other equipment ("Band Assisted Pull-Up", "Bodyweight Walking
         * Lunge", "Smith Incline Shoulder Raise"), so reading them would have the matcher refuse
         * matches the names alone make. Every catalogue row that starts with one of the rest is
         * filed under it, which `JefitCatalogueNamesTest` holds the seed to.
         */
        fun leadingEquipmentOf(name: String): EquipmentType? {
            val normalised = ExerciseMatcher.normalised(name)
            return LEADING.firstOrNull { label -> normalised.startsWith("$label ") }?.let(EQUIPMENT::get)
        }

        /** The labels in [EQUIPMENT] [leadingEquipmentOf] reads, longest first. */
        private val LEADING = (EQUIPMENT.keys - setOf("band", "resistance band", "bodyweight", "smith"))
            .sortedByDescending(String::length)

        /**
         * Whether [name] is the assisted variation of a movement — "Pull Up (Assisted)" — whose
         * weight is the counterweight a machine took off rather than a load.
         *
         * Both apps that write such names record the assistance as a positive number, and Titan
         * has no column for it. A parser reads the weight of such a set as not recorded, because
         * imported as a load it would make the easiest set the heaviest one; the reps are still
         * the set. This is the reading [ImportPlanner] gives a negative weight.
         */
        fun isAssisted(name: String): Boolean = split(name).second.equals(ASSISTED, ignoreCase = true)

        private const val ASSISTED = "Assisted"
    }
}
