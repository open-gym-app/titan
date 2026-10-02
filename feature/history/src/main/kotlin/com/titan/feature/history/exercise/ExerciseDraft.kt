package com.titan.feature.history.exercise

import androidx.compose.runtime.Immutable
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseMuscleGroupLink
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.MuscleGroup
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A user-authored exercise being written, whether it exists yet or not.
 *
 * One draft type and one form for both jobs. Creating an exercise from the library and
 * correcting one from its detail screen ask the same five questions, and two implementations
 * of that is how the two ends up disagreeing about whether a name may collide — see
 * [ExerciseForm], which is the single form both drive.
 *
 * The form reports the whole draft back on every change rather than one event per field.
 * Eleven `…Changed` events would all do the same thing — copy one field — and the owner would
 * still have to re-derive [isDuplicateName] after each of them.
 *
 * @param id the row being rewritten, or **null** for one that does not exist yet. It is the
 *   only thing that distinguishes the two jobs, and everything downstream reads it rather
 *   than a separate mode flag: [isNew] picks the form's title and its submit label, and the
 *   owner picks between an insert and an update.
 * @param primaryMuscleGroupId the prime mover. Required, and separate from
 *   [secondaryMuscleGroupIds] because volume attribution weights the two differently: a
 *   single "muscles worked" set would have to guess, and guessing "all primary" credits a
 *   triceps for every bench press.
 * @param isDuplicateName the catalogue already holds this name for this equipment, **on
 *   another row**. Derived by the owner from the loaded catalogue rather than discovered when
 *   the write fails, so the form can say so under the field instead of after the user presses
 *   save. The database's unique index is still the authority; this is the courtesy.
 * @param saveFailed the last write refused. Carried on the draft rather than shown as a
 *   snackbar, because the sheet is drawn *over* the screen that hosts the snackbar: a message
 *   raised behind it would play out and be marked shown without the user ever seeing it. It
 *   clears on the next edit, and does not block retrying the same content unchanged.
 *
 * `@Serializable` because a form somebody is typing into is the first thing a process death
 * must not cost them — the draft rides in its owner's saved screen state, so the sheet comes
 * back with what was in it. See `com.titan.core.ui.state.savedScreenState`.
 */
@Immutable
@Serializable
data class ExerciseDraft(
    val id: Long? = null,
    val name: String = "",
    val type: ExerciseType = ExerciseType.STRENGTH,
    val equipment: EquipmentType = EquipmentType.BARBELL,
    val primaryMuscleGroupId: Long? = null,
    val secondaryMuscleGroupIds: Set<Long> = emptySet(),
    val notes: String = "",
    /**
     * Not saved across a process death, unlike the rest of the draft. A write cannot still be
     * in flight when the process that started it is gone, and a form restored inert is one no
     * amount of tapping brings back.
     */
    @Transient
    val isSaving: Boolean = false,
    val isDuplicateName: Boolean = false,
    val saveFailed: Boolean = false,
) {

    /** Whether this describes an exercise that does not exist yet. */
    val isNew: Boolean
        get() = id == null

    /** Whether the form is complete, unique and not already being written. */
    val canSave: Boolean
        get() = name.isNotBlank() && primaryMuscleGroupId != null && !isDuplicateName && !isSaving
}

/**
 * The draft that edits [exercise], with [links] as its muscle selections.
 *
 * The inverse of [applyTo], and the reason the two are next to each other: a field this
 * reads and that one does not write is a field an edit silently reverts.
 */
internal fun exerciseDraftOf(
    exercise: Exercise,
    links: List<ExerciseMuscleGroupLink>,
): ExerciseDraft =
    ExerciseDraft(
        id = exercise.id,
        name = exercise.name,
        type = exercise.type,
        // The form has no chip for OTHER — `EquipmentType.labelOrNull` returns null for it — and
        // a null column is the ordinary case for a row written before the field existed. Either
        // way the form has to show something, and bodyweight is the honest default for an
        // exercise nobody recorded kit for.
        equipment = exercise.equipmentType?.takeIf { it != EquipmentType.OTHER }
            ?: EquipmentType.BODYWEIGHT,
        primaryMuscleGroupId = links.firstOrNull { it.isPrimary }?.muscleGroup?.id,
        secondaryMuscleGroupIds = links.filterNot { it.isPrimary }.mapTo(mutableSetOf()) { it.muscleGroup.id },
        notes = exercise.notes.orEmpty(),
    )

/** The exercise a completed *new* draft describes. */
internal fun ExerciseDraft.toExercise(): Exercise =
    Exercise(
        name = name.trim(),
        type = type,
        equipmentType = equipment,
        notes = notes.trim().ifEmpty { null },
        // The whole point of the flag: a custom exercise is the user's, and a future catalogue
        // refresh must not overwrite or drop it.
        isCustom = true,
        // No bundled image. `ExerciseImage` draws the name's initial, which is the honest
        // placeholder for an exercise nobody has photographed.
        icon = null,
    )

/**
 * [exercise] with the four fields this form edits taken from the draft.
 *
 * A copy of the loaded row rather than a fresh [Exercise] built from the draft alone, which
 * is the difference between an edit and a silent reset: the row carries fields the form does
 * not ask about — `defaultRest`, `instructions`, `isUnilateral` — and building from scratch
 * would blank every one of them on the first correction of a typo.
 */
internal fun ExerciseDraft.applyTo(exercise: Exercise): Exercise =
    exercise.copy(
        name = name.trim(),
        type = type,
        equipmentType = equipment,
        notes = notes.trim().ifEmpty { null },
    )

/**
 * The draft's muscle selections as domain links, primary first.
 *
 * Resolved against [taxonomy] rather than fabricated from the ids, so a group that has
 * vanished between the form opening and the save is dropped here instead of failing the
 * write's foreign key. An id selected as both primary and secondary yields one link, the
 * primary one — the form does not allow it, and a link table with a duplicate key would
 * reject the write rather than merge them.
 */
internal fun ExerciseDraft.resolveMuscleGroups(taxonomy: List<MuscleGroup>): List<ExerciseMuscleGroupLink> {
    val byId = taxonomy.associateBy { it.id }
    val primary = primaryMuscleGroupId?.let(byId::get)
    val secondaries = (secondaryMuscleGroupIds - setOfNotNull(primaryMuscleGroupId))
        .mapNotNull(byId::get)

    return listOfNotNull(primary?.let { ExerciseMuscleGroupLink(it, isPrimary = true) }) +
        secondaries.map { ExerciseMuscleGroupLink(it, isPrimary = false) }
}

/**
 * Whether another exercise already holds this draft's name for this equipment.
 *
 * Equipment is part of the comparison because it is part of the database's uniqueness rule —
 * a barbell row and a dumbbell row are two exercises, and a lifter who has both should be
 * able to say so. Case-insensitively, because the index is not the thing the user is trying
 * to satisfy; "bench press" beside "Bench Press" is a duplicate to anyone reading the list.
 *
 * The draft's own row is excluded, which is what makes an edit that changes only the notes
 * saveable rather than a duplicate of itself.
 */
internal fun ExerciseDraft.isDuplicateIn(catalogue: List<Exercise>): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false

    return catalogue.any { existing ->
        existing.id != id &&
            existing.name.equals(trimmed, ignoreCase = true) &&
            existing.equipmentType == equipment
    }
}
