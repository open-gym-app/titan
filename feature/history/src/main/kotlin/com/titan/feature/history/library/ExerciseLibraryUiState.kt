package com.titan.feature.history.library

import androidx.compose.runtime.Immutable
import com.titan.core.domain.model.EquipmentType
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseMuscleGroupLink
import com.titan.core.domain.model.MuscleGroup
import com.titan.core.domain.model.PersonalRecord
import com.titan.feature.history.exercise.ExerciseDraft

/**
 * Everything the exercise library draws, as one value.
 *
 * The list arrives already searched, filtered, grouped and counted — see
 * [ExerciseLibraryViewModel]. The screen renders [sections] in order and does no work of its
 * own to decide what goes in them, which is what lets a Compose test hand it four exercises
 * in two sections and assert on what is drawn.
 *
 * @param query what is in the search box. The screen owns no copy of it.
 * @param muscleGroups the muscle filter's vocabulary — the taxonomy, not just the groups the
 *   current results happen to use, so the row does not reshuffle as the user types.
 * @param selectedMuscleGroupId the muscle filter, or null for "all". One value rather than a
 *   set: the row opens with an ALL chip, and a filter with an "all" affordance is a choice
 *   between alternatives.
 * @param equipmentTypes the equipment filter's vocabulary: the kinds present in the
 *   catalogue, minus [EquipmentType.OTHER], which has no name to put on a chip.
 * @param selectedEquipment the equipment filter. A *set*, because "barbell or dumbbell" is a
 *   question a lifter actually asks, where "chest or quads" is not.
 * @param sections the results, grouped by what each exercise primarily trains. Empty when
 *   nothing matches — see [hasFilters] for which of the two empty states that is.
 * @param catalogueSize how many exercises exist, filters or no filters. The top bar's
 *   `LIBRARY · 873`.
 * @param matchCount how many are in [sections]. Equal to [catalogueSize] when nothing is
 *   filtered.
 * @param isLoading there is no answer for what is on screen yet — either the catalogue read has
 *   not landed, or the rows in hand are the previous query's. Distinct from "the catalogue is
 *   empty" and from "nothing matched", both of which would otherwise be drawn over a library
 *   that is merely still opening, or on the way back from a search that matched nothing.
 * @param selection the exercises picked, in the order they were picked — that order becomes
 *   the session's exercise order, so a `Set` would quietly shuffle the workout. Only
 *   meaningful in selection mode.
 * @param draft the new-exercise form, or null when it is closed. Always a *new* draft here —
 *   the library authors exercises, and correcting one is the detail screen's job.
 * @param message a one-shot outcome to show and forget.
 */
@Immutable
data class ExerciseLibraryUiState(
    val query: String = "",
    val muscleGroups: List<MuscleGroup> = emptyList(),
    val selectedMuscleGroupId: Long? = null,
    val equipmentTypes: List<EquipmentType> = emptyList(),
    val selectedEquipment: Set<EquipmentType> = emptySet(),
    val sections: List<ExerciseSection> = emptyList(),
    val catalogueSize: Int = 0,
    val matchCount: Int = 0,
    val isLoading: Boolean = true,
    val selection: List<Long> = emptyList(),
    val draft: ExerciseDraft? = null,
    val message: ExerciseLibraryMessage? = null,
) {

    /**
     * Whether either chip filter is on.
     *
     * Separate from [hasFilters] because the two answer different questions. This one is what
     * the filter button lights up for and what the filter sheet's clear appears for — both of
     * which are about the sheet's own contents, and neither of which should react to a search
     * the sheet does not show.
     */
    val hasChipFilters: Boolean
        get() = selectedMuscleGroupId != null || selectedEquipment.isNotEmpty()

    /** Whether anything is narrowing the list — which decides *which* empty state to draw. */
    val hasFilters: Boolean
        get() = query.isNotBlank() || hasChipFilters
}

/**
 * One muscle group's worth of results, and its heading.
 *
 * @param muscleGroup what these exercises primarily train, or **null** for the ones the
 *   catalogue links to no muscle at all. Null is a real group here rather than an error: the
 *   seed leaves a handful of stretches and carries unlinked, and dropping them would mean a
 *   library that does not list everything it has.
 */
@Immutable
data class ExerciseSection(
    val muscleGroup: MuscleGroup?,
    val exercises: List<LibraryExercise>,
)

/**
 * One row: the exercise, what it works, and its record if it has one.
 *
 * The three are assembled once here rather than looked up per row at draw time, because the
 * muscle links and the records arrive as maps over the whole catalogue and a row that reached
 * into them would be a lookup inside a `LazyColumn` item — repeated on every scroll pass.
 *
 * @param record the one record worth showing, or null for an exercise nobody has set one on
 *   yet — most of the catalogue, for most users — and the row simply omits the figure.
 */
@Immutable
data class LibraryExercise(
    val exercise: Exercise,
    val muscleGroups: List<ExerciseMuscleGroupLink> = emptyList(),
    val record: PersonalRecord? = null,
)

/**
 * Something that happened and needs saying once.
 *
 * An enum rather than a string, for the reason `SettingsMessage` gives: the ViewModel does
 * not reach for a `Context`, and every user-visible word stays in the screen's resources.
 */
enum class ExerciseLibraryMessage {

    /** A custom exercise was created. Worth confirming — the list it joins is 873 rows long. */
    EXERCISE_CREATED,

    /**
     * The insert failed **and the form is no longer up** to say so — the user dismissed the
     * sheet while the write was in flight. A failure with the sheet still open is reported on
     * the draft instead; see [ExerciseDraft.saveFailed].
     */
    SAVE_FAILED,
}
