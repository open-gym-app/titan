package com.opengym.feature.session.summary

import androidx.compose.runtime.Immutable
import com.opengym.core.domain.model.MuscleGroup
import com.opengym.core.domain.model.PersonalRecord
import com.opengym.core.domain.model.SessionStats
import com.opengym.core.domain.model.TemplateCategory
import com.opengym.core.ui.component.MuscleContribution

/**
 * Everything the session recap draws, as one value.
 *
 * ### One state, two modes
 *
 * The screen is drawn twice in the app's life: once the moment a workout ends, and again
 * whenever that workout is opened from history. They are the *same* screen — the same stats,
 * the same records, the same muscle breakdown — which is why [mode] is a field on one state
 * rather than two screens sharing a component. What differs is only what the recap is *for*;
 * see [SessionSummaryMode].
 *
 * @param mode which of the two the screen is. Set from the route argument and never changes.
 * @param header the session's name and window, or **null** while [isLoading] and for a
 *   session id that no longer resolves. Those are two different screens — a blank page and a
 *   "this is gone" page — which is why the flag is separate rather than inferred; see
 *   [isMissing].
 * @param stats the figures, straight from `SessionStatsCalculator`. Null exactly when
 *   [header] is.
 * @param records the personal records **set during this session**, with the exercise each
 *   belongs to. Empty is the ordinary case and draws no card at all — see [SummaryRecord].
 * @param muscles the muscle breakdown, longest bar first. Built from
 *   `MuscleVolumeCalculator`, scaled against the hardest-worked muscle rather than against a
 *   total that is not a whole — see [MuscleContribution].
 * @param primaryMuscles the groups worked as a prime mover, for the diagram. Split from
 *   [muscles] rather than derived in composition, so the picture and the bars agree by
 *   construction.
 * @param secondaryMuscles the groups that assisted.
 * @param exercises the per-exercise breakdown, in the order they were performed. Only
 *   exercises that contributed a completed set, which is the same rule
 *   `SessionStats.exerciseBreakdown` applies — a list longer than the tile above it counting
 *   the same thing is a screen contradicting itself.
 * @param notes the session's stored note, or null for one that has none.
 * @param notesDraft what is in the note field, or **null** when the note is not being edited.
 *   Null is the read state, so a screen asking "is the field open" asks one question.
 * @param isSavingNotes a note write is in flight. The field stays up and inert, so a failure
 *   has somewhere to be reported and the text is still there to retry with.
 * @param templateName what is in the "save as template" dialog's name field, or **null** when
 *   the dialog is closed. Same shape as [notesDraft] and for the same reason.
 * @param templateCategory what the dialog's category chips have selected, or null for a template
 *   saved without one — which is a real answer and not a missing value, see `TemplateCategory`.
 * @param isSavingTemplate the template write is in flight.
 * @param templateNameTaken the last attempt was refused because another template already carries
 *   the name. Drawn under the field rather than sent to the snackbar: it is the one refusal the
 *   user can fix without retyping anything, and the dialog is still up with their text in it.
 * @param nameDraft what is in the rename field, or **null** when the dialog is closed. Same
 *   shape as [notesDraft], and for the same reason.
 * @param isSavingName a rename write is in flight.
 * @param isLoading the reads have not come back yet.
 * @param message a one-shot outcome to show and forget.
 */
@Immutable
data class SessionSummaryUiState(
    val mode: SessionSummaryMode = SessionSummaryMode.JUST_FINISHED,
    val header: SummaryHeader? = null,
    val stats: SessionStats? = null,
    val records: List<SummaryRecord> = emptyList(),
    val muscles: List<MuscleContribution> = emptyList(),
    val primaryMuscles: List<MuscleGroup> = emptyList(),
    val secondaryMuscles: List<MuscleGroup> = emptyList(),
    val exercises: List<SummaryExerciseRow> = emptyList(),
    val notes: String? = null,
    val notesDraft: String? = null,
    val isSavingNotes: Boolean = false,
    val templateName: String? = null,
    val templateCategory: TemplateCategory? = null,
    val isSavingTemplate: Boolean = false,
    val templateNameTaken: Boolean = false,
    val nameDraft: String? = null,
    val isSavingName: Boolean = false,
    val isLoading: Boolean = true,
    val message: SessionSummaryMessage? = null,
) {

    /** A session id that no longer resolves to a row — as opposed to one still loading. */
    val isMissing: Boolean get() = !isLoading && header == null

    /**
     * How many muscle groups the session touched, for the fourth stat tile.
     *
     * [muscles] rather than a count carried alongside it: they are the same fact, and the one
     * that is drawn is the one that should be counted.
     */
    val musclesWorked: Int get() = muscles.size

    /**
     * Whether there is anything to save as a template.
     *
     * A session with no performed exercises has nothing to plan, and `CreateTemplateUseCase`
     * refuses it with `TemplateError.NoExercises` — better to draw the action inert than to
     * offer a button whose only outcome is an error the user could not have avoided.
     */
    val canSaveAsTemplate: Boolean get() = exercises.isNotEmpty()
}

/**
 * What the recap is *for*, which is the whole of what the two modes differ in.
 *
 * The stats, the records, the muscle map and the breakdown are identical in both. Splitting
 * them into two screens to express that difference would mean two copies of a layout that has
 * to keep saying the same thing about the same session.
 */
enum class SessionSummaryMode {

    /**
     * The payoff, shown the moment `FinishSessionUseCase` returns.
     *
     * The workout is over and the flow has to end somewhere, so this mode owns the exit:
     * "Done" is the way out and there is no back arrow, because there is nothing behind it —
     * the session that was being logged has been closed out and popped. #111 adds the XP and
     * level-up celebration here, and only here.
     */
    JUST_FINISHED,

    /**
     * The same recap, opened from the session list — a record rather than a result.
     *
     * No celebration affordances: nothing was just achieved, so nothing is announced. The
     * back arrow is the way out and returns to the list. Everything the user can still *do*
     * to the session — annotate it, save it as a template — stays, because those are as
     * useful a week later as they were on the day.
     */
    HISTORICAL,
}

/**
 * The session's identity and the window it occupied.
 *
 * Carried as three fields rather than as the `WorkoutSession` row because that is all the
 * header draws, and because [finishedAt] is a *decision* rather than a column — see the
 * ViewModel.
 *
 * @param name what the user called it, or **null** for a session started empty and never
 *   named. Not collapsed to a default here: the screen falls back to a date-derived title,
 *   and a default stored in the state would make "unnamed" indistinguishable from a session
 *   the user genuinely called that.
 * @param startedAt epoch millis UTC, the session's `createdAt`.
 * @param finishedAt epoch millis UTC, when it was closed out.
 */
@Immutable
data class SummaryHeader(
    val name: String?,
    val startedAt: Long,
    val finishedAt: Long,
)

/**
 * A record set during this session, and the exercise it was set on.
 *
 * The name travels with the record because a record carries only an `exerciseId`, and a card
 * that had to resolve it at draw time would do a map lookup per composition for a value that
 * changes when the catalogue does.
 *
 * @param exerciseName the exercise, or **null** when it is no longer in the catalogue. The
 *   record still stands — it is a thing the user did — so it is drawn with a fallback title
 *   rather than dropped.
 */
@Immutable
data class SummaryRecord(
    val record: PersonalRecord,
    val exerciseName: String?,
)

/**
 * One line of the per-exercise breakdown: what was done, and whether it was a best.
 *
 * The rep range is two fields rather than a formatted string because the two ends are
 * rendered as one figure when they agree (`4 × 10`) and as a range when they do not
 * (`4 × 5-10`), and that is a decision for the composable that has the string resources.
 *
 * @param sessionExerciseId the `session_exercise` row, which is what keys a list item. Not
 *   the exercise id: the same exercise performed twice in one session is two rows, and a key
 *   that collapsed them would drop one.
 * @param name the exercise, or **null** when it has been deleted from the catalogue since.
 *   Kept as a row rather than dropped, so the count in the stat tile and the length of this
 *   list cannot disagree.
 * @param setCount completed sets only, matching every other figure on the screen.
 * @param lowestReps the fewest reps in any of those sets, or null for an exercise that logs
 *   no reps at all — a timed hold, a distance.
 * @param highestReps the most reps in any of them, or null on the same condition.
 * @param topWeight the heaviest load, in milligrams, or null for a bodyweight or cardio
 *   exercise. Null draws nothing rather than `0 kg`, which is a weight nobody lifted.
 * @param record the personal record this session set on this exercise, or null. Drives the
 *   `PR` badge on the row — the second place a record is called out, after its own card,
 *   because the card answers "what did I beat" and the row answers "which of these was it".
 */
@Immutable
data class SummaryExerciseRow(
    val sessionExerciseId: Long,
    val name: String?,
    val setCount: Int,
    val lowestReps: Int?,
    val highestReps: Int?,
    val topWeight: Long?,
    val record: PersonalRecord? = null,
)

/**
 * Something that happened and needs saying once.
 *
 * An enum rather than a string, matching `NewSessionMessage` and `ExerciseDetailMessage`: the
 * ViewModel does not reach for a `Context`, and every user-visible word stays in the screen's
 * resources.
 *
 * There is no message for a successful note save. The note reappearing as written text is the
 * confirmation, and a snackbar over it would be the app congratulating itself. A template
 * save *does* get one, because its own outcome is off-screen — the template lands in a list
 * on another tab.
 */
enum class SessionSummaryMessage {

    /** A note could not be written. The field stays open with the text still in it. */
    NOTES_SAVE_FAILED,

    /** The name could not be written. The dialog stays open with the text still in it. */
    NAME_SAVE_FAILED,

    /** The template was written. Said, because what it produced is not on this screen. */
    TEMPLATE_SAVED,

    /*
     * There is no message for a name already in use.
     *
     * It is reported rather than resolved by appending a number — the user picked the name, and
     * a template silently saved as "Push Day 2" is one they will not find when they look for
     * the one they meant to overwrite — but it is reported *in the dialog*, which is still up
     * with the name in it. See [SessionSummaryUiState.templateNameTaken].
     */

    /** The template could not be written. Nothing was saved. */
    TEMPLATE_SAVE_FAILED,
}
