package com.opengym.core.domain.template

import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.UserPreferences
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
        assertEquals(Duration.ZERO, TemplateDuration.estimate(emptyList()))
    }

    @Test
    fun `one exercise is its sets of work plus the rests between them`() {
        // Three sets, and the rest after the last one is never taken.
        val estimate = TemplateDuration.estimate(listOf(exercise(sets = 3, rest = 60)))

        assertEquals(work * 3 + 60.seconds * 2, estimate)
    }

    @Test
    fun `each exercise carries its own rest`() {
        val estimate = TemplateDuration.estimate(
            listOf(exercise(sets = 2, rest = 180), exercise(sets = 3, rest = 30)),
        )

        assertEquals(work * 5 + 180.seconds * 2 + 30.seconds * 2, estimate)
    }

    @Test
    fun `an exercise with no rest of its own takes the default`() {
        val estimate = TemplateDuration.estimate(listOf(exercise(sets = 2, rest = null)))

        assertEquals(work * 2 + defaultRest, estimate)
    }

    @Test
    fun `the caller's default rest is what an exercise without one falls back to`() {
        val estimate = TemplateDuration.estimate(
            listOf(exercise(sets = 2, rest = null)),
            defaultRest = 10.seconds,
        )

        assertEquals(work * 2 + 10.seconds, estimate)
    }

    @Test
    fun `an exercise with no set count is assumed to plan three`() {
        val estimate = TemplateDuration.estimate(listOf(exercise(sets = null, rest = 60)))

        assertEquals(work * TemplateDuration.DEFAULT_SETS + 60.seconds * 2, estimate)
    }

    @Test
    fun `the wireframe's eight-exercise template lands under an hour`() {
        val estimate = TemplateDuration.estimate(List(8) { exercise(sets = 3, rest = null) })

        // 24 sets of work, and 23 rests: the 24th is the end of the workout.
        assertEquals(work * 24 + defaultRest * 23, estimate)
        assertEquals(52.5.minutes, estimate)
    }

    @Test
    fun `a superset's members are worked back to back with no rest between them`() {
        val estimate = TemplateDuration.estimate(
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

        val superset = TemplateDuration.estimate(paired)
        val separately = TemplateDuration.estimate(paired.map { it.copy(supersetGroup = null) })

        assertEquals(60.seconds * 3, separately - superset)
    }

    @Test
    fun `only the last member of a superset contributes a rest`() {
        val estimate = TemplateDuration.estimate(
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
    fun `an exercise planning no sets adds nothing`() {
        val estimate = TemplateDuration.estimate(
            listOf(exercise(sets = 2, rest = 60), exercise(sets = 0, rest = 60)),
        )

        assertEquals(work * 2 + 60.seconds, estimate)
    }

    @Test
    fun `a template of nothing but empty exercises takes no time`() {
        val estimate = TemplateDuration.estimate(List(3) { exercise(sets = 0, rest = 60) })

        assertEquals(Duration.ZERO, estimate)
    }

    @Test
    fun `the rest dropped is the last one actually taken, not the last exercise's`() {
        val estimate = TemplateDuration.estimate(
            listOf(exercise(sets = 2, rest = 60), exercise(sets = 0, rest = 3_600)),
        )

        // The hour-long rest is never reached, so it is not what gets subtracted.
        assertEquals(work * 2 + 60.seconds, estimate)
    }

    @Test
    fun `a negative set count contributes nothing rather than subtracting time`() {
        val estimate = TemplateDuration.estimate(
            listOf(exercise(sets = -4, rest = 60), exercise(sets = 2, rest = 60)),
        )

        assertEquals(work * 2 + 60.seconds, estimate)
    }

    @Test
    fun `a negative rest is read as no rest at all`() {
        val estimate = TemplateDuration.estimate(
            listOf(exercise(sets = 2, rest = -90), exercise(sets = 2, rest = 60)),
        )

        assertEquals(work * 4 + 60.seconds, estimate)
    }

    @Test
    fun `a negative default rest is read as no rest at all`() {
        val estimate = TemplateDuration.estimate(
            listOf(exercise(sets = 2, rest = null), exercise(sets = 2, rest = null)),
            defaultRest = (-90).seconds,
        )

        assertEquals(work * 4, estimate)
    }

    @Test
    fun `editing the exercises changes the estimate, with nothing stored in between`() {
        val before = listOf(exercise(sets = 3, rest = 60))
        val after = before + exercise(sets = 4, rest = 90)

        val added = TemplateDuration.estimate(after) - TemplateDuration.estimate(before)

        // The new exercise's work and rests, plus the rest that the first exercise's
        // final set now does get, having stopped being the end of the workout.
        assertEquals(work * 4 + 90.seconds * 3 + 60.seconds, added)
    }

    @Test
    fun `shortening a rest shortens the estimate`() {
        val before = listOf(exercise(sets = 3, rest = 120), exercise(sets = 3, rest = 120))
        val after = before.map { it.copy(restDuration = 60) }

        assertEquals(60.seconds * 5, TemplateDuration.estimate(before) - TemplateDuration.estimate(after))
    }

    @Test
    fun `a draft estimates the same as the row it becomes`() {
        val drafts = listOf(
            TemplateExerciseDraft(exerciseId = 1, defaultSets = 3, restDuration = 60, supersetGroup = 0),
            TemplateExerciseDraft(exerciseId = 2, defaultSets = 3, restDuration = 60, supersetGroup = 0),
            TemplateExerciseDraft(exerciseId = 3, defaultSets = 4, restDuration = null),
        )
        val stored = drafts.mapIndexed { position, draft ->
            TemplateExercise(
                id = position + 1L,
                templateId = 1,
                exerciseId = draft.exerciseId,
                position = position,
                supersetGroup = draft.supersetGroup,
                defaultSets = draft.defaultSets,
                restDuration = draft.restDuration,
            )
        }

        assertEquals(TemplateDuration.estimate(stored), TemplateDuration.estimate(drafts))
    }

    private fun exercise(sets: Int?, rest: Long? = null, group: Int? = null) = TemplateExercise(
        templateId = 1,
        exerciseId = 1,
        position = 0,
        supersetGroup = group,
        defaultSets = sets,
        restDuration = rest,
    )
}
