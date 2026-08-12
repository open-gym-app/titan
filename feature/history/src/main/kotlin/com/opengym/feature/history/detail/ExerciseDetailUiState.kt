package com.opengym.feature.history.detail

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.ExerciseMuscleGroupLink
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.feature.history.exercise.ExerciseDraft

/**
 * Everything one exercise's screen draws, as one value.
 *
 * @param exercise the row, or **null** while [isLoading] and for an id that no longer exists.
 *   Those two are different screens — a blank page and a "this is gone" page — which is why
 *   the flag is separate rather than inferred from an empty name.
 * @param muscleLinks what it trains, primary movers first, as the repository orders them. The
 *   links rather than two lists, because the diagram and the written legend need the same
 *   split and deriving it twice is how the two come to disagree.
 * @param taxonomy the muscle vocabulary the edit form picks from. Empty until it loads, which
 *   only matters when the form is open.
 * @param notesDraft what is in the note field, or **null** when the note is not being edited.
 *   Null is the read state, so a screen asking "is the field open" is asking one question.
 * @param isSavingNotes a note write is in flight. The field stays up and inert rather than
 *   snapping back to read mode, so a failure has somewhere to be reported and the text is
 *   still there to retry with.
 * @param editDraft the whole-exercise form, or null when it is closed. Only ever non-null for
 *   a custom exercise — see [canEdit].
 * @param confirmingDelete the delete confirmation is up.
 * @param isDeleting the delete is in flight. Separate from [isDeleted]: the dialog's button
 *   goes inert on the first and the screen leaves on the second.
 * @param isDeleted the exercise was deleted *by this screen*. The signal to leave, and the
 *   reason a deletion does not simply fall out of [exercise] going null — that would draw the
 *   "no longer exists" page for a frame on the way out.
 * @param message a one-shot outcome to show and forget.
 */
@Immutable
data class ExerciseDetailUiState(
    val exercise: Exercise? = null,
    val muscleLinks: List<ExerciseMuscleGroupLink> = emptyList(),
    val taxonomy: List<MuscleGroup> = emptyList(),
    val notesDraft: String? = null,
    val isSavingNotes: Boolean = false,
    val editDraft: ExerciseDraft? = null,
    val confirmingDelete: Boolean = false,
    val isDeleting: Boolean = false,
    val isDeleted: Boolean = false,
    val isLoading: Boolean = true,
    val message: ExerciseDetailMessage? = null,
) {

    /** The id that no longer resolves to a row — as opposed to one that has not loaded yet. */
    val isMissing: Boolean
        get() = !isLoading && exercise == null && !isDeleted

    /**
     * Whether this exercise can be rewritten or removed.
     *
     * Only the user's own. The seeded catalogue is a bundled dataset that a catalogue
     * refresh replaces wholesale, so an edit to one of them would be silently undone by the
     * next release — and a delete would leave a hole that reinstalling fills back in. The
     * user's own remarks on a seeded exercise are a different thing entirely, live in
     * [Exercise.notes], and are editable on every exercise; see [notesDraft].
     */
    val canEdit: Boolean
        get() = exercise?.isCustom == true

    /**
     * The muscles the movement is *for*. Drawn in the accent, listed first.
     *
     * Split once, at construction, rather than behind a `get()`: the screen reads these while
     * composing and a getter would re-filter and re-allocate on every read, which is a new
     * list identity each time for a value that only changes when [muscleLinks] does.
     */
    val primaryMuscles: List<MuscleGroup> =
        muscleLinks.filter { it.isPrimary }.map { it.muscleGroup }

    /** The muscles that assist. Drawn in the accent's container tone. */
    val secondaryMuscles: List<MuscleGroup> =
        muscleLinks.filterNot { it.isPrimary }.map { it.muscleGroup }

    /**
     * The how-to, one step per line, or empty for an exercise that has none.
     *
     * The seed writes `instructions` as newline-separated steps (PERSISTENCE.md § Seed
     * data) and the screen numbers them, so the split belongs here rather than in
     * composition — and is computed once here rather than per read, since the body asks
     * twice, once to decide whether to draw the section and once for its contents. Blank
     * lines are dropped: a numbered list with an empty item 4 in it looks like a rendering
     * fault.
     */
    val instructionSteps: List<String> = exercise?.instructions
        ?.lines()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
}

/**
 * Something that happened and needs saying once.
 *
 * An enum rather than a string, for the reason `ExerciseLibraryMessage` gives: the ViewModel
 * does not reach for a `Context`, and every user-visible word stays in the screen's resources.
 *
 * There is no message for a *successful* note save. The note reappearing as written text is
 * the confirmation, and a snackbar over it would be the app congratulating itself.
 */
enum class ExerciseDetailMessage {

    /** A note could not be written. The field stays open with the text still in it. */
    NOTES_SAVE_FAILED,

    /** The whole-exercise edit was written. Worth confirming: the sheet closes over it. */
    EXERCISE_UPDATED,

    /**
     * The edit refused **and the form is no longer up** to say so — the user dismissed the
     * sheet while the write was in flight. A failure with the sheet still open is reported on
     * the draft instead; see [ExerciseDraft.saveFailed].
     */
    EXERCISE_SAVE_FAILED,

    /**
     * The delete refused. Said here rather than on the dialog, which has closed by then —
     * unlike the edit form, a confirmation has nothing left to hold the message.
     */
    DELETE_FAILED,
}
