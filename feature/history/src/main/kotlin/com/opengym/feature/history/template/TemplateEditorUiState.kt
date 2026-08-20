package com.opengym.feature.history.template

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.Exercise
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.domain.model.TemplateSummary
import com.opengym.core.domain.model.WorkoutTemplate
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
 * @param naming the name-and-category dialog's contents, or **null** when it is closed.
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
    val naming: TemplateNamingDraft? = null,
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
    val hasErrors: Boolean get() = exercises.any { it.errors.isNotEmpty() }

    /**
     * Whether SAVE does anything.
     *
     * Four conditions, and each is one the use case would otherwise refuse: a template needs a
     * name and at least one exercise, a figure that is not a number cannot be stored, and a save
     * of a draft nobody has changed is a write that replaces every exercise row with itself.
     */
    val canSave: Boolean
        get() = isDirty && !isSaving && !hasErrors &&
            name.isNotBlank() && exercises.isNotEmpty()

    /** Whether the page takes input at all. False while a write is out or the read is not in. */
    val isEditable: Boolean get() = !isLoading && !isMissing && !isSaving
}

/**
 * One exercise of the plan, with its defaults as they are being typed.
 *
 * ### Why the four figures are `String`
 *
 * Because a half-typed number is not a number. `SetLogRow` makes the same trade for the same
 * reason: a field holding an `Int?` cannot represent "the user has typed a minus sign", and one
 * that parsed on every keystroke would delete the character somebody was in the middle of.
 * Parsing happens here too — see [errors] — but what is *held* is what was typed.
 *
 * @param key identity across a reorder, stable for the life of the draft. Not the stored
 *   `TemplateExercise.id`, which an exercise added in this session does not have yet, and not the
 *   exercise id, which the same movement twice in one plan would share.
 * @param exercise the catalogue row, or **null** for an exercise deleted from the library since
 *   the template was saved. It keeps its place rather than vanishing: the plan is the user's, and
 *   a line silently dropped is a workout that quietly got shorter.
 * @param weight in the ambient unit system's unit, like every weight field in the app.
 * @param rest in seconds. Blank means the template says nothing and the session will use the
 *   user's configured default.
 * @param supersetLabel the `A1`/`A2` marker, or null for an exercise that stands alone. Derived
 *   from the grouping over the whole list, which a row does not have.
 * @param isLinkedToNext whether this exercise and the one after it are one superset — what the
 *   join between the two cards draws.
 * @param errors which of the four fields holds something that cannot be stored.
 */
@Immutable
data class TemplateExerciseRow(
    val key: Long,
    val exerciseId: Long,
    val exercise: Exercise? = null,
    val sets: String = "",
    val reps: String = "",
    val weight: String = "",
    val rest: String = "",
    val supersetGroup: Int? = null,
    val supersetLabel: String? = null,
    val isLinkedToNext: Boolean = false,
    val errors: Set<TemplateField> = emptySet(),
)

/** The four figures a template line carries, as the thing an error is *about*. */
enum class TemplateField {
    SETS,
    REPS,
    WEIGHT,
    REST,
}

/**
 * The name-and-category dialog's contents while it is up.
 *
 * Nothing it holds is written when it is confirmed — it changes the draft, and the draft is
 * written by SAVE. [nameTaken] is therefore set from the *save's* refusal rather than the
 * dialog's own: the collision cannot be known until the write is attempted, and the answer to it
 * is this dialog, reopened with the name still in it.
 */
@Immutable
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

    /*
     * There is no message for a template that has gone.
     *
     * A save refused with `TemplateError.NotFound` — the template deleted from the tab while
     * this was open — puts the screen into [TemplateEditorUiState.isMissing], which replaces the
     * page with the dead end and its way out. That says it better than a snackbar over an editor
     * for something that no longer exists.
     */

    /** The template could not be written. Nothing was changed; the draft is still here. */
    SAVE_FAILED,
}
