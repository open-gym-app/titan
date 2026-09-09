package com.opengym.feature.history.template

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.SetType
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.WorkoutTemplate
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Everything the template editor draws, as one value.
 *
 * ### It is a draft, not the template
 *
 * Nothing here is written until SAVE. The whole point of the screen is rearranging a plan —
 * dropping an exercise, pairing two, changing what a set should be — and every one of those is a
 * decision the user is entitled to back out of. Writing as they went would also mean writing the
 * *body* on every keystroke, since `UpdateTemplateUseCase` replaces the exercise list wholesale.
 *
 * That is what [isDirty] is for, and why leaving with it set asks first.
 *
 * @param name what the template is called, as edited. The stored name until the user changes it.
 * @param category what it trains, as edited. Null is a real answer — see [TemplateCategory].
 * @param exercises the plan, in the order it will be performed.
 * @param estimatedDuration the pacing figure for the plan *as edited*, recomputed as exercises
 *   are added, dropped and repriced. Derived and never stored — see
 *   [com.opengym.core.domain.template.TemplateDuration].
 * @param unitSystem the unit every weight on the page is *typed and parsed in*, read once with
 *   the template. Carried here rather than read from `LocalUnitSystem` at the cell, because a
 *   setting changed while this screen sits on the tab's back stack would otherwise relabel a
 *   column of figures the save still parses in the old unit.
 * @param naming the name-and-category dialog's contents, or **null** when it is closed.
 * @param isNew this is a template being built rather than one being changed. Decides the title,
 *   what backing out says, and which use case SAVE calls — and nothing else, which is the point:
 *   creating and editing are one screen because they are one question. See
 *   `com.opengym.feature.history.navigation.NEW_TEMPLATE`.
 * @param isLoading the read has not come back yet.
 * @param isMissing the read came back with no such template. Distinct from loading: one is a
 *   blank screen for a moment, the other is a dead end with a way out.
 * @param isSaving the write is in flight. The page goes inert rather than closing, so a refusal
 *   has somewhere to be reported and the draft is still there to fix.
 * @param isDirty the draft differs from what is stored. Decides whether SAVE is live and whether
 *   backing out asks first.
 * @param isPickingExercises the exercise picker is open over this screen. Held so a second tap on
 *   "add exercises" through a slow transition cannot push a second picker.
 * @param confirmingDiscard the user is being asked whether to throw the edits away.
 * @param isSaved the write landed and the screen is done. The screen reports it and `:app` pops;
 *   carried in the state rather than pushed down a channel because there is nothing to race.
 * @param message a one-shot outcome to show and forget.
 */
@Immutable
data class TemplateEditorUiState(
    val name: String = "",
    val category: TemplateCategory? = null,
    val exercises: List<TemplateExerciseRow> = emptyList(),
    val estimatedDuration: Duration = Duration.ZERO,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val naming: TemplateNamingDraft? = null,
    val isNew: Boolean = false,
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val isSaving: Boolean = false,
    val isDirty: Boolean = false,
    val isPickingExercises: Boolean = false,
    val confirmingDiscard: Boolean = false,
    val isSaved: Boolean = false,
    val message: TemplateEditorMessage? = null,
) {

    /**
     * The header row, which is the same [com.opengym.core.ui.component.TemplateRow] the tab and
     * the new-session picker draw.
     *
     * Built from the *draft* rather than from the stored row, so the count and the estimate move
     * as the plan is edited: a header still reading "8 ex · ~55m" over a list of five is the
     * screen contradicting itself.
     */
    val summary: TemplateSummary
        get() = TemplateSummary(
            template = WorkoutTemplate(name = name, category = category),
            exerciseCount = exercises.size,
            estimatedDuration = estimatedDuration,
        )

    /** Whether anything in the plan was typed wrong. Blocks the save rather than the field. */
    val hasErrors: Boolean get() = exercises.any(TemplateExerciseRow::hasErrors)

    /**
     * Whether SAVE does anything.
     *
     * Each is one the use case would otherwise refuse: a template needs a name and at least one
     * exercise, a figure that is not a number cannot be stored, a save of a draft nobody has
     * changed is a write that replaces every exercise row with itself, and a template that is
     * not there any more has nothing to save into — the page is the dead end by then, and a live
     * SAVE above it is a button whose only outcome is the refusal that put it there.
     */
    val canSave: Boolean
        get() = isDirty && !isSaving && !isMissing && !hasErrors &&
            name.isNotBlank() && exercises.isNotEmpty()

    /** Whether the page takes input at all. False while a write is out or the read is not in. */
    val isEditable: Boolean get() = !isLoading && !isMissing && !isSaving

    /**
     * There is a form on the page, so the things that sit around one belong there too.
     *
     * Distinct from [isEditable], which also goes false while a write is out: a save in flight
     * takes the page's controls out of service, it does not take the page away — the save bar
     * stays where it is, greyed, rather than vanishing from under the thumb that just used it.
     */
    val isEditorDrawn: Boolean get() = !isLoading && !isMissing
}

/**
 * One exercise of the plan, and the sets it lays out.
 *
 * ### Why the figures are `String`
 *
 * Because a half-typed number is not a number. `SetLogRow` makes the same trade for the same
 * reason: a field holding an `Int?` cannot represent "the user has typed a minus sign", and one
 * that parsed on every keystroke would delete the character somebody was in the middle of.
 * Parsing happens here too — see [TemplateSetRow.errors] — but what is *held* is what was typed.
 *
 * Rest is not on the row. The workout's timer runs on the user's configured default whatever a
 * template holds, so a rest planned here would be a figure nothing ever read — see
 * `TemplateExerciseCard`.
 *
 * @param key identity across a reorder, stable for the life of the draft. Not the stored
 *   `TemplateExercise.id`, which an exercise added in this session does not have yet, and not the
 *   exercise id, which the same movement twice in one plan would share.
 * @param exercise the catalogue row, or **null** for an exercise deleted from the library since
 *   the template was saved. It keeps its place rather than vanishing: the plan is the user's, and
 *   a line silently dropped is a workout that quietly got shorter.
 * @param sets what to perform, in order — a warm-up, the working sets, whatever ends it. Empty is
 *   legal and means the line plans an exercise and leaves its sets to the day; the session then
 *   opens it with nothing but its entry row, exactly as one built by hand.
 * @param supersetLabel the `A1`/`A2` marker, or null for an exercise that stands alone. Derived
 *   from the grouping over the whole list, which a row does not have.
 * @param supersetGroupName the group's own name — the `A` of `A1` — or null for an exercise that
 *   stands alone. Carried beside [supersetLabel] rather than sliced out of it: the label's format
 *   belongs to `SupersetLabels`, and its numeric fallback past the twenty-sixth group is all
 *   digits, which a slice on the first digit reads as an empty name.
 * @param isLinkedToNext whether this exercise and the one after it are one superset — what the
 *   join between the two cards draws.
 */
@Immutable
data class TemplateExerciseRow(
    val key: Long,
    val exerciseId: Long,
    val exercise: Exercise? = null,
    val sets: List<TemplateSetRow> = emptyList(),
    val supersetGroup: Int? = null,
    val supersetLabel: String? = null,
    val supersetGroupName: String? = null,
    val isLinkedToNext: Boolean = false,
) {

    /** Whether anything on this line was typed wrong, wherever on it. */
    val hasErrors: Boolean get() = sets.any { it.errors.isNotEmpty() }

    /** Whether another set can be added. See [TemplateEditorUiState] for the ceiling. */
    val canAddSet: Boolean get() = sets.size < MAX_SETS_PER_EXERCISE
}

/**
 * One set of the plan, as the row it is typed into.
 *
 * The counterpart of the workout screen's `LoggedSet`, minus everything a set that has not
 * happened cannot have: no id, no tick, no rating, no previous. What is left is what a plan
 * actually says — which kind of set it is, and the two figures.
 *
 * @param key identity within its exercise, stable for the life of the draft, so a keystroke that
 *   lands after a set was inserted above still reaches the set it was typed into.
 * @param setType the warm-up, the working set, the drop set. The whole reason the plan is a list.
 * @param weight in the ambient unit system's unit, like every weight field in the app.
 * @param reps how many.
 * @param errors which of the two figures holds something that cannot be stored.
 */
@Immutable
data class TemplateSetRow(
    val key: Long,
    val setType: SetType = SetType.NORMAL,
    val weight: String = "",
    val reps: String = "",
    val errors: Set<TemplateSetField> = emptySet(),
)

/** The two figures a planned set carries, as the thing an error is *about*. */
enum class TemplateSetField {
    WEIGHT,
    REPS,
}

/**
 * How many sets one exercise of a template may plan.
 *
 * A sanity limit rather than a domain rule — there is no `SetValidator` bound for it, because a
 * *session* counts its sets rather than declaring them. Ninety-nine rows is far past any real
 * plan and well short of a list that a phone struggles to draw, and the ceiling exists so that a
 * finger held on ADD SET cannot build one.
 */
const val MAX_SETS_PER_EXERCISE = 99

/**
 * The name-and-category dialog's contents while it is up.
 *
 * Nothing it holds is written when it is confirmed — it changes the draft, and the draft is
 * written by SAVE. [nameTaken] is therefore set from the *save's* refusal rather than the
 * dialog's own: the collision cannot be known until the write is attempted, and the answer to it
 * is this dialog, reopened with the name still in it.
 *
 * `@Serializable` because it holds typed text: it rides in the editor's saved screen state, so a
 * name half-entered when the process died is still in the field when the user comes back.
 */
@Immutable
@Serializable
data class TemplateNamingDraft(
    val name: String,
    val category: TemplateCategory? = null,
    val nameTaken: Boolean = false,
)

/**
 * Something that happened and needs saying once.
 *
 * An enum rather than a string, matching every other screen: the ViewModel does not reach for a
 * `Context`, and every user-visible word stays in the screen's resources.
 *
 * There is no message for a successful save. The screen closes on it, and a snackbar over the
 * list the user lands back on would be describing a screen they have already left.
 */
enum class TemplateEditorMessage {

    /**
     * Some of the exercises picked are no longer in the catalogue and were left out.
     *
     * An exercise deleted from the library between the picker listing it and the answer landing.
     * Rare, and reported rather than swallowed: the plan is not the one the user assembled.
     */
    EXERCISES_UNAVAILABLE,

    /**
     * The exercise picked for a swap is no longer in the catalogue, so the slot was left alone.
     *
     * [EXERCISES_UNAVAILABLE]'s case for a swap, which is worth its own word because the outcome
     * is different: an add that loses one of three still adds two, where a swap that loses its
     * one answer changes nothing at all, and a screen that said "some were left out" about a
     * plan that is exactly as it was would be describing something that did not happen.
     */
    EXERCISE_UNAVAILABLE,

    /*
     * There is no message for a template that has gone.
     *
     * A save refused with `TemplateError.NotFound` — the template deleted from the tab while
     * this was open — puts the screen into [TemplateEditorUiState.isMissing], which replaces the
     * page with the dead end and its way out. That says it better than a snackbar over an editor
     * for something that no longer exists.
     */

    /**
     * The exercises picked were not added, because this editor never asked for them.
     *
     * An answer with no request behind it — see `TemplateEditorViewModel.pickRequest`. The
     * request is saved state now and the plan is a file (#183), so the case this used to
     * describe, a process killed while the user was in the picker, restores both and applies the
     * pick like any other. What is left is an answer redelivered after it was already consumed,
     * or one belonging to a screen this is not; appending either would put exercises in a plan
     * nobody asked to put them in, so it is refused and said out loud.
     */
    EDITOR_RESTARTED,

    /** The template could not be written. Nothing was changed; the draft is still here. */
    SAVE_FAILED,
}
