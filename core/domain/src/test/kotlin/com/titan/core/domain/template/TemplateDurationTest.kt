package com.titan.core.domain.template

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
 * than as a figure: `4 * (45 + 60)` says which rule produced the number, where `420`
 * would only say that this run agreed with the last one.
 */
class TemplateDurationTest {

    private val work = TemplateDuration.WORK_PER_SET
    private val defaultRest = UserPreferences.DEFAULT_REST_DURATION

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
    fun `each exercise carries its own rest`() {
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
    fun `the wireframe's eight-exercise template lands under an hour`() {
        val estimate = estimate(List(8) { exercise(sets = 3, rest = null) })

        // 24 sets of work, and 23 rests: the 24th is the end of the workout.
        assertEquals(work * 24 + defaultRest * 23, estimate)
        assertEquals(52.5.minutes, estimate)
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
    fun `a negative rest is read as no rest at all`() {
        val estimate = estimate(
            listOf(exercise(sets = 2, rest = -90), exercise(sets = 2, rest = 60)),
        )

        assertEquals(work * 4 + 60.seconds, estimate)
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
    fun `shortening a rest shortens the estimate`() {
        val before = listOf(exercise(sets = 3, rest = 120), exercise(sets = 3, rest = 120))
        val after = before.map { it.copy(restDuration = 60) }

        assertEquals(60.seconds * 5, estimate(before) - estimate(after))
    }

    @Test
    fun `a draft estimates the same as the row it becomes`() {
        val drafts = listOf(
            TemplateExerciseDraft(
                exerciseId = 1,
                sets = List(3) { PlannedSet() },
                restDuration = 60,
                supersetGroup = 0,
            ),
            TemplateExerciseDraft(
                exerciseId = 2,
                sets = List(3) { PlannedSet() },
                restDuration = 60,
                supersetGroup = 0,
            ),
            TemplateExerciseDraft(exerciseId = 3, sets = List(4) { PlannedSet() }, restDuration = null),
        )
        val stored = drafts.mapIndexed { position, draft ->
            TemplateExercise(
                id = position + 1L,
                templateId = 1,
                exerciseId = draft.exerciseId,
                position = position,
                supersetGroup = draft.supersetGroup,
                sets = draft.sets,
                restDuration = draft.restDuration,
            )
        }

        assertEquals(estimate(stored), estimate(drafts))
    }

    /**
     * [TemplateDuration.estimate] with the user's configured default rest, which the
     * function requires and most cases here do not vary.
     */
    // ─── Timed sets (#M7-17) ─────────────────────────────────────────────────────────────

    @Test
    fun `a planned hold takes as long as it is planned for, not a rep tempo`() {
        val plank = exercise(sets = 0, rest = 30).copy(sets = List(3) { PlannedSet(duration = 45) })

        assertEquals(45.seconds * 3 + 30.seconds * 2, estimate(listOf(plank)))
    }

    @Test
    fun `a planned run is its own length`() {
        val run = exercise(sets = 0, rest = 60).copy(sets = listOf(PlannedSet(duration = 1_800, distance = 500_000)))

        assertEquals(30.minutes, estimate(listOf(run)))
    }

    @Test
    fun `a set with no planned time keeps the allowance, beside one that has one`() {
        val mixed = exercise(sets = 0, rest = 60).copy(sets = listOf(PlannedSet(reps = 8), PlannedSet(duration = 120)))

        assertEquals(work + 120.seconds + 60.seconds, estimate(listOf(mixed)))
    }

    @Test
    fun `negative - a non-positive planned time falls back to the allowance rather than to nothing`() {
        val imported = exercise(
            sets = 0,
            rest = 60,
        ).copy(sets = listOf(PlannedSet(duration = 0), PlannedSet(duration = -5)))

        assertEquals(work * 2 + 60.seconds, estimate(listOf(imported)))
    }

    private fun estimate(
        exercises: List<PlannedExercise>,
        rest: Duration = defaultRest,
    ) = TemplateDuration.estimate(exercises, rest)

    private fun exercise(
        sets: Int,
        rest: Long? = null,
        group: Int? = null,
    ) = TemplateExercise(
        templateId = 1,
        exerciseId = 1,
        position = 0,
        supersetGroup = group,
        // Sets with no planned time, so each is priced at the allowance — only how many there
        // are matters here. The planned-time cases build their own. See `TemplateDuration`.
        sets = List(sets) { PlannedSet() },
        restDuration = rest,
    )
}
