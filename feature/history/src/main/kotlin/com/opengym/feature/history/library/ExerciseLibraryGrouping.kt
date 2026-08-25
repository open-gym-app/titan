package com.opengym.feature.history.library

import com.opengym.core.domain.model.EquipmentType
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PersonalRecord

/*
 * The library's rules, as pure functions over the rows.
 *
 * Extracted from the ViewModel because they are the part worth testing directly: what an
 * intersection of three filters means, which muscle group a section belongs to, and what
 * order the sections come in. None of them touches a repository, a Flow or a scope, so a test
 * for "an exercise with two primary movers is filed under the first" is four lines and no
 * coroutine.
 */

/**
 * The intersection of the muscle and equipment filters.
 *
 * The query is not applied here — it has already run, in SQLite. These two are what is left,
 * and they combine as an *intersection with each other* while the equipment set is a union
 * within itself: "a chest exercise, done with a barbell or a dumbbell". That asymmetry is the
 * one the two filters actually have, and flattening it would make either "chest and quads"
 * expressible (it is not one exercise) or "barbell or dumbbell" inexpressible (it is a normal
 * question).
 *
 * A muscle matches on **any** link, primary or secondary: an overhead press works the chest
 * and a lifter looking for chest work should be shown it.
 */
internal fun List<Exercise>.filterBy(
    muscleGroupId: Long?,
    equipment: Set<EquipmentType>,
    links: Map<Long, List<ExerciseMuscleGroupLink>>,
): List<Exercise> =
    filter { exercise ->
        val muscleMatches = muscleGroupId == null ||
            links[exercise.id].orEmpty().any { it.muscleGroup.id == muscleGroupId }
        val equipmentMatches = equipment.isEmpty() || exercise.equipmentType in equipment
        muscleMatches && equipmentMatches
    }

/**
 * Group into sections by prime mover, ordered by muscle group name, unlinked exercises last.
 *
 * **The prime mover is the first link**, which the repository documents as the ordering its
 * links arrive in — primary first, then alphabetical. Reading "the first one" rather than
 * "the one flagged primary" is one rule instead of three: it lands on the primary where there
 * is one, on the alphabetically first secondary for the handful the seed leaves without one,
 * and on the [ExerciseSection.muscleGroup] null section for the ones it links to nothing at
 * all. An exercise the user gave two primaries to is filed under one of them rather than
 * appearing twice, which is what a *grouped list* means.
 *
 * Sections are ordered by the stored [MuscleGroup.name] — the seed's `SCREAMING_SNAKE` key —
 * rather than by the label the user reads. The two agree for the seeded taxonomy, and the
 * alternative is either resolving seventeen string resources in a ViewModel that has no
 * `Context`, or re-sorting the list inside composition on every recomposition.
 *
 * Exercises keep the order they arrive in, which the DAO has already sorted by name.
 */
internal fun List<Exercise>.groupIntoSections(
    links: Map<Long, List<ExerciseMuscleGroupLink>>,
    records: Map<Long, PersonalRecord>,
): List<ExerciseSection> =
    groupBy { exercise -> links[exercise.id]?.firstOrNull()?.muscleGroup }
        .entries
        .sortedWith(compareBy(nullsLast()) { (group, _) -> group?.name })
        .map { (group, exercises) ->
            ExerciseSection(
                muscleGroup = group,
                exercises = exercises.map { exercise ->
                    LibraryExercise(
                        exercise = exercise,
                        muscleGroups = links[exercise.id].orEmpty(),
                        record = records[exercise.id],
                    )
                },
            )
        }

/**
 * The equipment kinds worth offering as chips: the ones the catalogue actually contains, in
 * the enum's own order.
 *
 * [EquipmentType.OTHER] is left out because it has no label — `EquipmentType.labelOrNull()`
 * returns null for it deliberately — so a chip for it would be a blank capsule that filtered
 * to a bucket of unrelated kit. Exercises with no equipment recorded at all are likewise not
 * offered, for the same reason: there is nothing to write on the chip.
 *
 * Enum order rather than alphabetical, because the enum is declared roughly by how common the
 * kit is, and a filter row that opens with `Band, Barbell, Bodyweight, Cable` buries the two
 * chips most people want behind the two they do not.
 */
internal fun List<Exercise>.equipmentVocabulary(): List<EquipmentType> {
    val present = mapNotNullTo(mutableSetOf()) { it.equipmentType }
    return EquipmentType.entries.filter { it != EquipmentType.OTHER && it in present }
}

/*
 * The draft's own rules — what row it describes, and how its chip selections become muscle
 * links — live with the draft, in `exercise/ExerciseDraft.kt`. They moved there when the
 * detail screen started editing the same drafts this screen creates.
 */
