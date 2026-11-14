package com.titan.core.domain.importer

import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import java.text.Normalizer
import java.util.Locale

/**
 * Finds the exercise already on the device that an [ImportedExercise] names, if there is one.
 *
 * Matching to an existing row rather than creating one per source name is what makes an imported
 * history part of the user's history: a bench press imported from Strong and one logged here
 * yesterday are one exercise, with one set of records and one progression chart. An import that
 * created "Bench Press (Barbell)" beside the catalogue's own bench press would leave the user two
 * of everything and a records screen that compares neither against the other.
 *
 * ### The rule
 *
 * A candidate is an exercise whose name equals the imported one once both are [normalised]
 * — [ImportedExercise.catalogueName] first, when the parser has one, then
 * [ImportedExercise.name]. Of the candidates, one is kept only if:
 *
 * - **its type is the type the imported sets were logged as.** A match that changed how a set has
 *   to be filled in would have the planner drop every set the source recorded: Strong's "Farmer's
 *   Walk" logged by distance and time is not the catalogue's rep-counted one, and keeping the
 *   history beats keeping the name. A type mismatch falls through to a custom exercise.
 * - **its equipment agrees, when the source says what it was.** "Squat" on a barbell and "Squat"
 *   with nothing in hand are different movements with different records. A source that says
 *   nothing about equipment matches any.
 *
 * Several survivors — the catalogue and a custom exercise of the same name, say — resolve to the
 * lowest id, which is the catalogue row when there is one, and the same row every time.
 *
 * This deliberately does no fuzzy matching. A wrong match silently files somebody's history
 * under another movement and merges its records with that movement's; a missed match costs a
 * custom exercise the user can see and rename. Closing the gap between an app's vocabulary and
 * the catalogue's is the parser's job, through [ImportedExercise.catalogueName].
 */
class ExerciseMatcher(existing: List<Exercise>) {

    private val byName: Map<String, List<Exercise>> = existing
        .sortedBy(Exercise::id)
        .groupBy { normalised(it.name) }

    /** The existing exercise [imported] is, logged as [type], or null when there is none. */
    fun match(
        imported: ImportedExercise,
        type: ExerciseType,
    ): Exercise? =
        listOfNotNull(imported.catalogueName, imported.name)
            .asSequence()
            .mapNotNull { name -> byName[normalised(name)] }
            .mapNotNull { candidates -> candidates.firstOrNull { it.fits(type, imported.equipment) } }
            .firstOrNull()

    private fun Exercise.fits(
        type: ExerciseType,
        equipment: EquipmentType?,
    ): Boolean = this.type == type && (equipment == null || equipmentType == null || equipmentType == equipment)

    companion object {

        private val COMBINING_MARKS = Regex("\\p{M}+")
        private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        /**
         * [name] reduced to what two spellings of one exercise name have in common: no accents, no
         * case, no punctuation, single spaces.
         *
         * So "Barbell Bench Press - Medium Grip", "barbell bench press (medium grip)" and
         * "Barbell  Bench Press, Medium-Grip" are one name, and "Développé couché" matches
         * "developpe couche". Word order is kept: "Press Bench" is not a bench press.
         */
        fun normalised(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replace(COMBINING_MARKS, "")
                .lowercase(Locale.ROOT)
                .replace(SEPARATORS, " ")
                .trim()
    }
}
