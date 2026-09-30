package com.titan.feature.progression.exercise

import androidx.compose.runtime.Immutable
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseAttempt
import com.titan.core.domain.model.ExerciseProgression
import com.titan.core.domain.model.TrainedExercise
import com.titan.feature.progression.stats.StatsTimeframe

/**
 * Everything the per-exercise drill-down draws, as one value.
 *
 * ### Canonical figures, not formatted ones
 *
 * Milligrams, milligram-reps, counts and epoch millis, exactly as [ExerciseProgressionUiState]'s
 * counterpart on the hub carries them and for the same reason: formatting reads
 * `LocalUnitSystem`, which is ambient in the composition, so a ViewModel that formatted would
 * hold state that goes stale the moment the user switches to pounds.
 *
 * ### Two screens in one, chosen by [exercise]
 *
 * With no lift selected this is the **chooser**: [choices] and nothing else. With one it is the
 * drill-down. They are one destination rather than two because the chooser is also the
 * switcher — the screen is opened from the hub with no lift in hand and from an exercise's own
 * page with one, and both want the other state one tap away.
 *
 * @param timeframe the window the series below are read over. Shared with the hub — see
 *   [com.titan.feature.progression.stats.ProgressionTimeframe] — so a lifter who set the hub
 *   to `1Y` and opened a lift is not shown ninety days.
 * @param isLoading true until every read has answered once. One flag rather than one per
 *   query, because the reads are combined and the first combined value arrives only when all
 *   of them have.
 * @param exercise the lift being read, or **null** for the chooser. Also null for an id that
 *   no longer resolves, which draws the chooser rather than a dead end: the honest answer to
 *   "that lift is gone" on a screen whose whole job is picking one is the list of the others.
 * @param choices every exercise the user has logged, most recently trained first. Drawn as the
 *   chooser, and read for the switcher's own list.
 * @param progression the readings, or **null** while [exercise] is. All-time records and set
 *   count, plus the window's attempts — see [ExerciseProgression] for why those two scopes
 *   differ.
 * @param isExerciseSelected whether a lift is being read, which [exercise] cannot answer while
 *   the reads are in flight: a screen loading one lift and a screen showing the chooser both
 *   carry a null [exercise]. What the timeframe selector is drawn on, so the control the user
 *   just tapped does not vanish under their thumb while the new window is being read.
 */
@Immutable
data class ExerciseProgressionUiState(
    val timeframe: StatsTimeframe = StatsTimeframe.DEFAULT,
    val isLoading: Boolean = true,
    val exercise: Exercise? = null,
    val choices: List<TrainedExercise> = emptyList(),
    val progression: ExerciseProgression? = null,
    val isExerciseSelected: Boolean = exercise != null,
) {

    /**
     * Why there is not enough here to draw, or **null** when there is.
     *
     * Computed here rather than assembled by the ViewModel because it is a pure reading of
     * [progression] — a preview that built one state and a different emptiness would be
     * describing a screen the app cannot reach.
     *
     * [ExerciseEmptiness.NEVER_TRAINED] also asks the window to be empty, which by rights it
     * always is when the all-time count is zero. The two figures come off two queries the
     * combine can emit a mixed pair of, though — the attempts land a beat before the count on
     * the read that follows a finished session — and "not logged yet" over a session the user
     * has just done is the one wrong answer here worth spending a condition on.
     */
    val emptiness: ExerciseEmptiness? = when {
        progression == null -> null
        progression.isEmpty && progression.totalSets == 0 -> ExerciseEmptiness.NEVER_TRAINED
        progression.isEmpty -> ExerciseEmptiness.QUIET_PERIOD
        !progression.hasTrend -> ExerciseEmptiness.SINGLE_SESSION
        else -> null
    }

    /**
     * The attempts as the table reads them: newest first, and only the recent ones.
     *
     * The series are oldest first, because that is the direction a chart is read in, and the
     * table is the same list the other way up. Reversed once here rather than at the call
     * sites that want it, so the two orders cannot fall out of step.
     *
     * **Capped**, unlike the charts, which draw the whole window. `RECENT ATTEMPTS` is a
     * heading that promises the last few and an `ALL` window on a lift somebody has trained
     * for three years is three hundred rows — laid out eagerly, since the drill-down is one
     * scrolling column rather than a lazy list, and read by nobody past the first screenful.
     * The charts are where the whole window is answered.
     */
    val recentAttempts: List<ExerciseAttempt> =
        progression?.attempts?.asReversed()?.take(MAX_RECENT_ATTEMPTS).orEmpty()
}

/**
 * How many sessions the table draws.
 *
 * Eight, which is a couple of months of a lift trained twice a week — far enough back to see
 * what the last block did, and short enough to still be a table rather than a history. The
 * wireframes draw five; eight fills the screen the charts leave without adding a scroll of its
 * own.
 */
private const val MAX_RECENT_ATTEMPTS = 8

/**
 * Why one lift's screen has nothing much to say.
 *
 * Three states rather than one flag, because they want three different things said to them
 * and no amount of looking at the figures tells them apart — the same argument
 * [com.titan.feature.progression.stats.StatsEmptiness] makes for the hub, with one more
 * case: a lift can have a history and still be too new to have a *trend*.
 */
enum class ExerciseEmptiness {

    /**
     * Never logged, in any window. Reachable only from an exercise's own page, since the
     * chooser is built from what the user has actually trained.
     */
    NEVER_TRAINED,

    /**
     * Trained before, but not in this window. The screen says so and offers a wider one rather
     * than telling somebody with three years on a lift to go and do it.
     */
    QUIET_PERIOD,

    /**
     * Exactly one session in the window. The records and the attempt are drawn; the charts are
     * not, because a series of one point is a bar filling its own track — a comparison that has
     * not been made. See [ExerciseProgression.hasTrend].
     */
    SINGLE_SESSION,
}
