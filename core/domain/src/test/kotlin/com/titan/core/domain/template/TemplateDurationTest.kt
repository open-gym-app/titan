package com.titan.core.domain.template

import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PlannedExercise
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.TemplateExercise
import com.titan.core.domain.model.TemplateExerciseDraft
import com.titan.core.domain.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The estimate is arithmetic, so the assertions are written as the arithmetic rather
 * than as a figure: `4 * (30 + 60)` says which rule produced the number, where `360`
 * would only say that this run agreed with the last one.
 *
 * A case's `rest` is the catalogue exercise's own `defaultRest` — the only per-exercise rest
 * the workout's timer runs, and so the only one the estimate prices.
 */
class TemplateDurationTest {

    private val work = TemplateDuration.WORK_PER_SET
    private val defaultRest = UserPreferences.DEFAULT_REST_DURATION

    /** The catalogue every case's exercises are registered in, one entry per [exercise] call. */
    private val catalogue = mutableMapOf<Long, Exercise>()

    @Test
    fun `a template with no exercises takes no time`() {
        assertEquals(Duration.ZERO, estimate(emptyList()))
    }

    @Test
    fun `one exercise is its sets of work plus the rests between them`() {
        // Three sets, and the rest after the last one is never taken.
        val estimate = estimate(listOf(exercise(sets = 3, rest = 60)))

        assertEquals(work * 3 + 60.seconds * 2, estimate)
    }

    @Test
    fun `each exercise rests as long as its catalogue entry says`() {
        val estimate = estimate(
            listOf(exercise(sets = 2, rest = 180), exercise(sets = 3, rest = 30)),
        )

        assertEquals(work * 5 + 180.seconds * 2 + 30.seconds * 2, estimate)
    }

    @Test
    fun `an exercise with no rest of its own takes the default`() {
        val estimate = estimate(listOf(exercise(sets = 2, rest = null)))

        assertEquals(work * 2 + defaultRest, estimate)
    }

    @Test
    fun `the caller's default rest is what an exercise without one falls back to`() {
        val estimate = estimate(
            listOf(exercise(sets = 2, rest = null)),
            rest = 10.seconds,
        )

        assertEquals(work * 2 + 10.seconds, estimate)
    }

    @Test
    fun `an exercise that plans no sets is not performed and costs nothing`() {
        // It used to be charged three sets, because a template could not tell "no plan" from
        // "no sets" — one nullable count said both. A plan is a list now, and an empty one
        // says exactly one thing.
        val estimate = estimate(listOf(exercise(sets = 0, rest = 60)))

        assertEquals(Duration.ZERO, estimate)
    }

    @Test
    fun `a typical six-exercise template on the default rest takes an hour`() {
        val estimate = estimate(List(6) { exercise(sets = 3, rest = null) })

        // 18 sets of work, and 17 rests: the 18th is the end of the workout.
        assertEquals(work * 18 + defaultRest * 17, estimate)
        assertEquals(60.minutes, estimate)
    }

    @Test
    fun `the shipped allowances are thirty seconds a set and three minutes a rest`() {
        assertEquals(30.seconds, work)
        assertEquals(3.minutes, defaultRest)
    }

    @Test
    fun `a superset's members are worked back to back with no rest between them`() {
        val estimate = estimate(
            listOf(
                exercise(sets = 3, rest = 60, group = 0),
                exercise(sets = 3, rest = 60, group = 0),
            ),
        )

        // Three rounds of both exercises, resting after each round but not the last.
        assertEquals(work * 6 + 60.seconds * 2, estimate)
    }

    @Test
    fun `a superset is quicker than the same exercises performed separately`() {
        val paired = listOf(
            exercise(sets = 3, rest = 60, group = 0),
            exercise(sets = 3, rest = 60, group = 0),
        )

        val superset = estimate(paired)
        val separately = estimate(paired.map { it.copy(supersetGroup = null) })

        assertEquals(60.seconds * 3, separately - superset)
    }

    @Test
    fun `only the last member of a superset contributes a rest`() {
        val estimate = estimate(
            listOf(
                exercise(sets = 2, rest = 30, group = 0),
                exercise(sets = 2, rest = 30, group = 0),
                exercise(sets = 2, rest = 30, group = 0),
                exercise(sets = 2, rest = 45),
            ),
        )

        // Six sets in the superset over two rounds, one rest between them; then a
        // standalone exercise, whose own last rest is the end of the workout.
        assertEquals(work * 8 + 30.seconds + 30.seconds + 45.seconds, estimate)
    }

    @Test
    fun `a superset runs for as many rounds as its longest member`() {
        val estimate = estimate(
            listOf(
                exercise(sets = 4, rest = 60, group = 0),
                exercise(sets = 2, rest = 60, group = 0),
            ),
        )

        // Four rounds, the second exercise dropping out after the second; the rest is
        // taken after each round but the last, so four rests and not the shorter
        // member's two.
        assertEquals(work * 6 + 60.seconds * 3, estimate)
    }

    @Test
    fun `a superset whose last member plans no sets still rests after the one performed`() {
        val estimate = estimate(
            listOf(
                exercise(sets = 3, rest = 30, group = 0),
                exercise(sets = 0, rest = 30, group = 0),
            ),
        )

        // The empty member is not performed, so this is one exercise for three sets and
        // the group's rest is due after it, not lost with the member that never runs.
        assertEquals(work * 3 + 30.seconds * 2, estimate)
    }

    @Test
    fun `an empty last member neither loses its group's rest nor drops an earlier one`() {
        val estimate = estimate(
            listOf(
                exercise(sets = 3, rest = 60),
                exercise(sets = 3, rest = 30, group = 0),
                exercise(sets = 0, rest = 30, group = 0),
            ),
        )

        // The standalone exercise's three rests are all taken — the third is the
        // transition into the superset — and the workout ends on the group's own last
        // rest, which is the one dropped.
        assertEquals(work * 6 + 60.seconds * 3 + 30.seconds * 2, estimate)
    }

    @Test
    fun `the rest before an empty superset is one that does get taken`() {
        val estimate = estimate(
            listOf(
                exercise(sets = 3, rest = 60),
                exercise(sets = 0, rest = 30, group = 0),
                exercise(sets = 0, rest = 30, group = 0),
            ),
        )

        // Neither member of the superset runs, so the workout ends on the standalone
        // exercise and it is *its* last rest that is dropped — the two before it are
        // stood through.
        assertEquals(work * 3 + 60.seconds * 2, estimate)
    }

    @Test
    fun `an exercise planning no sets adds nothing`() {
        val estimate = estimate(
            listOf(exercise(sets = 2, rest = 60), exercise(sets = 0, rest = 60)),
        )

        assertEquals(work * 2 + 60.seconds, estimate)
    }

    @Test
    fun `a template of nothing but empty exercises takes no time`() {
        val estimate = estimate(List(3) { exercise(sets = 0, rest = 60) })

        assertEquals(Duration.ZERO, estimate)
    }

    @Test
    fun `the rest dropped is the last one actually taken, not the last exercise's`() {
        val estimate = estimate(
            listOf(exercise(sets = 2, rest = 60), exercise(sets = 0, rest = 3_600)),
        )

        // The hour-long rest is never reached, so it is not what gets subtracted.
        assertEquals(work * 2 + 60.seconds, estimate)
    }

    /*
     * There is no test for a negative set count any more, because there is no way to write
     * one: the plan is a list, and a list cannot be -4 long. The rest below still guards its
     * own negative case, which an import genuinely can produce.
     */

    @Test
    fun `a non-positive exercise rest is no opinion, and the default is taken`() {
        // The timer reads it the same way — see `RestDurations` — and the estimate has to
        // price the rest the timer will run.
        val estimate = estimate(
            listOf(exercise(sets = 2, rest = -90), exercise(sets = 2, rest = 0), exercise(sets = 2, rest = 60)),
        )

        assertEquals(work * 6 + defaultRest * 4 + 60.seconds, estimate)
    }

    @Test
    fun `a negative default rest is read as no rest at all`() {
        val estimate = estimate(
            listOf(exercise(sets = 2, rest = null), exercise(sets = 2, rest = null)),
            rest = (-90).seconds,
        )

        assertEquals(work * 4, estimate)
    }

    @Test
    fun `editing the exercises changes the estimate, with nothing stored in between`() {
        val before = listOf(exercise(sets = 3, rest = 60))
        val after = before + exercise(sets = 4, rest = 90)

        val added = estimate(after) - estimate(before)

        // The new exercise's work and rests, plus the rest that the first exercise's
        // final set now does get, having stopped being the end of the workout.
        assertEquals(work * 4 + 90.seconds * 3 + 60.seconds, added)
    }

    @Test
    fun `shortening an exercise's rest shortens the estimate`() {
        val plan = listOf(exercise(sets = 3, rest = 120), exercise(sets = 3, rest = 120))
        val before = estimate(plan)

        plan.forEach { catalogue[it.exerciseId] = catalogue.getValue(it.exerciseId).copy(defaultRest = 60) }

        assertEquals(60.seconds * 5, before - estimate(plan))
    }

    @Test
    fun `changing the default rest moves every exercise without one of its own`() {
        val plan = listOf(exercise(sets = 3, rest = null), exercise(sets = 3, rest = 45))

        val longer = estimate(plan, rest = 4.minutes) - estimate(plan, rest = 2.minutes)

        // The first exercise's three rests move; the second keeps its own 45 seconds.
        assertEquals(2.minutes * 3, longer)
    }

    @Test
    fun `a draft estimates the same as the row it becomes`() {
        val first = exercise(sets = 3, rest = 60, group = 0)
        val second = exercise(sets = 3, rest = 60, group = 0)
        val third = exercise(sets = 4, rest = null)
        val drafts = listOf(first, second, third).map { row ->
            TemplateExerciseDraft(exerciseId = row.exerciseId, sets = row.sets, supersetGroup = row.supersetGroup)
        }
        val stored = drafts.mapIndexed { position, draft ->
            TemplateExercise(
                id = position + 1L,
                templateId = 1,
                exerciseId = draft.exerciseId,
                position = position,
                supersetGroup = draft.supersetGroup,
                sets = draft.sets,
            )
        }

        assertEquals(estimate(stored), estimate(drafts))
    }

    // ─── Timed sets (#M7-17) ─────────────────────────────────────────────────────────────

    @Test
    fun `a planned hold takes as long as it is planned for, not a rep tempo`() {
        val plank = exercise(sets = 0, rest = 30, type = ExerciseType.ISOMETRIC)
            .copy(sets = List(3) { PlannedSet(duration = 45) })

        assertEquals(45.seconds * 3 + 30.seconds * 2, estimate(listOf(plank)))
    }

    @Test
    fun `a planned run is its own length`() {
        val run = exercise(sets = 0, rest = 60, type = ExerciseType.ENDURANCE)
            .copy(sets = listOf(PlannedSet(duration = 1_800, distance = 500_000)))

        assertEquals(30.minutes, estimate(listOf(run)))
    }

    @Test
    fun `a set with no planned time keeps the allowance, beside one that has one`() {
        val mixed = exercise(sets = 0, rest = 60, type = ExerciseType.ISOMETRIC)
            .copy(sets = listOf(PlannedSet(reps = 8), PlannedSet(duration = 120)))

        assertEquals(work + 120.seconds + 60.seconds, estimate(listOf(mixed)))
    }

    @Test
    fun `negative - a non-positive planned time falls back to the allowance rather than to nothing`() {
        val imported = exercise(sets = 0, rest = 60, type = ExerciseType.ISOMETRIC)
            .copy(sets = listOf(PlannedSet(duration = 0), PlannedSet(duration = -5)))

        assertEquals(work * 2 + 60.seconds, estimate(listOf(imported)))
    }

    @Test
    fun `a strength set's planned time is not priced, because no field shows it`() {
        // A 30-minute run slot swapped for bench press: the set keeps its time, in a field the
        // strength row does not draw.
        val swapped = exercise(sets = 0, rest = 60, type = ExerciseType.STRENGTH)
            .copy(sets = List(2) { PlannedSet(duration = 1_800) })

        assertEquals(work * 2 + 60.seconds, estimate(listOf(swapped)))
    }

    @Test
    fun `a planned time is priced for an exercise the catalogue no longer has`() {
        val orphan = exercise(sets = 0).copy(sets = listOf(PlannedSet(duration = 120)))
        catalogue.remove(orphan.exerciseId)

        assertEquals(120.seconds, estimate(listOf(orphan)))
    }

    @Test
    fun `an exercise the catalogue no longer has rests for the default`() {
        val orphan = exercise(sets = 2, rest = 30)
        catalogue.remove(orphan.exerciseId)

        assertEquals(work * 2 + defaultRest, estimate(listOf(orphan)))
    }

    // ─── Reps ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a set of ten reps costs exactly the allowance`() {
        // The tempo was chosen so: reading reps must not move a template planned in tens.
        assertEquals(work, TemplateDuration.SECONDS_PER_REP * 10)
    }

    @Test
    fun `a strength set takes as long as its reps`() {
        val ramp = exercise(sets = 0, rest = 60)
            .copy(sets = listOf(PlannedSet(reps = 3), PlannedSet(reps = 20)))

        assertEquals(TemplateDuration.SECONDS_PER_REP * (3 + 20) + 60.seconds, estimate(listOf(ramp)))
    }

    @Test
    fun `a strength set with no reps keeps the allowance, beside one that has them`() {
        val mixed = exercise(sets = 0, rest = 60)
            .copy(sets = listOf(PlannedSet(reps = 5), PlannedSet()))

        assertEquals(TemplateDuration.SECONDS_PER_REP * 5 + work + 60.seconds, estimate(listOf(mixed)))
    }

    @Test
    fun `a timed set's reps are not priced, because no field shows them`() {
        // A bench slot swapped for a run keeps its reps, in a field the endurance row does not
        // draw — the mirror of the strength set's hidden time.
        val swapped = exercise(sets = 0, rest = 60, type = ExerciseType.ENDURANCE)
            .copy(sets = List(2) { PlannedSet(reps = 50) })

        assertEquals(work * 2 + 60.seconds, estimate(listOf(swapped)))
    }

    @Test
    fun `reps are priced for an exercise the catalogue no longer has`() {
        val orphan = exercise(sets = 0).copy(sets = listOf(PlannedSet(reps = 4)))
        catalogue.remove(orphan.exerciseId)

        assertEquals(TemplateDuration.SECONDS_PER_REP * 4, estimate(listOf(orphan)))
    }

    @Test
    fun `negative - a rep count SetValidator refuses is priced at the allowance`() {
        val imported = exercise(sets = 0, rest = 60)
            .copy(sets = listOf(PlannedSet(reps = 0), PlannedSet(reps = Int.MAX_VALUE)))

        assertEquals(work * 2 + 60.seconds, estimate(listOf(imported)))
    }

    // ─── The breakdown ───────────────────────────────────────────────────────────────────

    @Test
    fun `the breakdown sums to the estimate, with the last rest taken off the exercise that had it`() {
        val bench = exercise(sets = 3, rest = 120)
        val run = exercise(sets = 0, rest = 60, type = ExerciseType.ENDURANCE)
            .copy(sets = listOf(PlannedSet(duration = 600)))
        val exercises = listOf(bench, run)

        val breakdown = TemplateDuration.breakdown(exercises, defaultRest, catalogue)

        assertEquals(
            listOf(
                TemplateDuration.ExerciseTime(bench.exerciseId, ExerciseType.STRENGTH, work * 3, 120.seconds * 3),
                // Its one rest is the workout's last, which nobody takes.
                TemplateDuration.ExerciseTime(run.exerciseId, ExerciseType.ENDURANCE, 600.seconds, Duration.ZERO),
            ),
            breakdown,
        )
        assertEquals(estimate(exercises), breakdown.fold(Duration.ZERO) { sum, slice -> sum + slice.work + slice.rest })
    }

    @Test
    fun `a superset member that is not last rests nothing in the breakdown`() {
        val exercises = listOf(exercise(sets = 2, rest = 90, group = 1), exercise(sets = 2, rest = 60, group = 1))

        val rests = TemplateDuration.breakdown(exercises, defaultRest, catalogue).map { it.rest }

        assertEquals(listOf(Duration.ZERO, 60.seconds), rests)
    }

    @Test
    fun `an exercise that plans no sets has no slice`() {
        val breakdown = TemplateDuration.breakdown(
            listOf(exercise(sets = 0), exercise(sets = 1)),
            defaultRest,
            catalogue,
        )

        assertEquals(listOf(2L), breakdown.map { it.exerciseId })
    }

    // ─── Values only a restored backup can hold (#256) ───────────────────────────────────

    /**
     * `Long.MAX_VALUE` seconds saturated to an infinite rest, and the estimate subtracted the
     * infinite trailing rest from an infinite total, which throws: Home crashed at launch.
     */
    @Test
    fun `an exercise rest longer than a day falls back to the default`() {
        val estimate = estimate(listOf(exercise(sets = 2, rest = Long.MAX_VALUE)))

        assertEquals(work * 2 + defaultRest, estimate)
    }

    @Test
    fun `a default rest past the ceiling is priced at the ceiling`() {
        val estimate = estimate(listOf(exercise(sets = 2, rest = null)), rest = Duration.INFINITE)

        assertEquals(work * 2 + UserPreferences.MAX_REST_DURATION, estimate)
    }

    @Test
    fun `a planned time longer than SetValidator admits is priced at the allowance`() {
        val hold = exercise(sets = 0, rest = 60, type = ExerciseType.ISOMETRIC)
            .copy(sets = List(2) { PlannedSet(duration = Long.MAX_VALUE) })

        assertEquals(work * 2 + 60.seconds, estimate(listOf(hold)))
    }

    /**
     * [TemplateDuration.estimate] with the user's configured default rest, over [catalogue] —
     * which the function requires and most cases here do not vary.
     */
    private fun estimate(
        exercises: List<PlannedExercise>,
        rest: Duration = defaultRest,
    ) = TemplateDuration.estimate(exercises, rest, catalogue)

    /**
     * A planned exercise over a catalogue entry of its own, whose `defaultRest` is [rest]. A
     * fresh id each call, so two exercises in one case rest independently.
     */
    private fun exercise(
        sets: Int,
        rest: Long? = null,
        group: Int? = null,
        type: ExerciseType = ExerciseType.STRENGTH,
    ): TemplateExercise {
        val id = catalogue.size + 1L
        catalogue[id] = Exercise(id = id, name = "Exercise $id", type = type, defaultRest = rest)
        return TemplateExercise(
            templateId = 1,
            exerciseId = id,
            position = 0,
            supersetGroup = group,
            // Sets with no planned time, so each is priced at the allowance — only how many there
            // are matters here. The planned-time cases build their own. See `TemplateDuration`.
            sets = List(sets) { PlannedSet() },
        )
    }
}
