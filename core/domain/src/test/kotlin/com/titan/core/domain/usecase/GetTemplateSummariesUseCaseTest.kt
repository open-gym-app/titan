package com.titan.core.domain.usecase

import app.cash.turbine.test
import com.titan.core.domain.integration.FakeExerciseRepository
import com.titan.core.domain.model.Exercise
import com.titan.core.domain.model.ExerciseType
import com.titan.core.domain.model.PlannedSet
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateSummary
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.template.TemplateDuration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GetTemplateSummariesUseCaseTest {

    private val templates = FakeTemplateRepository()
    private val preferences = FakeUserPreferencesRepository()
    private val catalogue = FakeExerciseRepository(
        listOf(
            Exercise(id = 1L, name = "Bench press", type = ExerciseType.STRENGTH),
            Exercise(id = 2L, name = "Plank", type = ExerciseType.ISOMETRIC),
        ),
    )
    private val summaries = GetTemplateSummariesUseCase(templates, preferences, catalogue)

    @Test
    fun `summarising carries the template's own fields through`() =
        runTest {
            templates.seed("Push day", listOf(draft(exerciseId = 1L)), category = TemplateCategory.STRENGTH)

            val summary = summaries().first().single()

            assertEquals("Push day", summary.template.name)
            assertEquals(TemplateCategory.STRENGTH, summary.template.category)
        }

    @Test
    fun `summarising counts the exercises a template lays out`() =
        runTest {
            templates.seed("Push day", listOf(draft(exerciseId = 1L), draft(exerciseId = 2L)))
            templates.seed("Leg day", listOf(draft(exerciseId = 3L)))

            assertEquals(listOf(1, 2), summaries().first().map(TemplateSummary::exerciseCount))
        }

    @Test
    fun `summarising counts a superset's members individually`() =
        runTest {
            // What the row says is what the user is about to perform, and a superset is two
            // exercises done back to back — not one exercise.
            templates.seed(
                "Push day",
                listOf(
                    draft(exerciseId = 1L, supersetGroup = 0),
                    draft(exerciseId = 2L, supersetGroup = 0),
                ),
            )

            assertEquals(2, summaries().first().single().exerciseCount)
        }

    @Test
    fun `summarising estimates the duration from the template's contents`() =
        runTest {
            val body = listOf(
                draft(exerciseId = 1L, sets = plannedSets(3)),
                draft(exerciseId = 2L, sets = plannedSets(3)),
            )
            templates.seed("Push day", body)

            val summary = summaries().first().single()

            assertEquals(
                TemplateDuration.estimate(
                    exercises = templates.storedExercises(1L),
                    defaultRest = UserPreferences().defaultRestDuration,
                    exerciseTypes = mapOf(1L to ExerciseType.STRENGTH, 2L to ExerciseType.ISOMETRIC),
                ),
                summary.estimatedDuration,
            )
        }

    @Test
    fun `summarising prices the estimate off the user's own default rest`() =
        runTest {
            // The reason the preferences are read here rather than left to a screen: an
            // estimate against the shipped default is right on most phones and quietly wrong
            // on the ones belonging to users who changed it.
            templates.seed("Push day", listOf(draft(exerciseId = 1L, sets = plannedSets(3))))
            val shipped = summaries().first().single().estimatedDuration

            preferences.setDefaultRestDuration(300.seconds)

            val theirs = summaries().first().single().estimatedDuration
            assertEquals(true, theirs > shipped)
        }

    @Test
    fun `editing a template's exercises changes its estimate with no write`() =
        runTest {
            val id = templates.seed("Push day", listOf(draft(exerciseId = 1L, sets = plannedSets(3))))
            val writesBefore = templates.writeCount

            summaries().test {
                val before = awaitItem().single().estimatedDuration

                templates.update(
                    templates.stored(id)!!,
                    listOf(
                        draft(exerciseId = 1L, sets = plannedSets(3)),
                        draft(exerciseId = 2L, sets = plannedSets(3)),
                    ),
                )

                assertEquals(true, awaitItem().single().estimatedDuration > before)
                cancelAndIgnoreRemainingEvents()
            }

            // The one update above and nothing else: no estimate was persisted alongside it.
            assertEquals(writesBefore + 1, templates.writeCount)
        }

    @Test
    fun `a template whose exercises were removed underneath summarises as empty`() =
        runTest {
            templates.seedEmpty("Push day")

            val summary = summaries().first().single()

            assertEquals(0, summary.exerciseCount)
            assertEquals(Duration.ZERO, summary.estimatedDuration)
        }

    @Test
    fun `no templates summarises to nothing`() =
        runTest {
            assertEquals(emptyList<TemplateSummary>(), summaries().first())
        }

    @Test
    fun `summarising re-sorts a store that hands its exercises back out of order`() =
        runTest {
            // Position order is what both the estimate and the superset grouping are read in,
            // so the sort is the same defence `ApplyTemplateUseCase` keeps — and, as there,
            // only a store that breaks the promise can show it doing anything.
            templates.seed(
                "Push day",
                listOf(
                    draft(exerciseId = 1L, sets = plannedSets(3), supersetGroup = 0),
                    draft(exerciseId = 2L, sets = plannedSets(3), supersetGroup = 0),
                    draft(exerciseId = 3L, sets = plannedSets(1)),
                ),
            )
            val ordered = summaries().first().single().estimatedDuration

            templates.honoursPositionOrder = false

            assertEquals(ordered, summaries().first().single().estimatedDuration)
        }

    @Test
    fun `summarising reads the catalogue's types for which planned times count`() =
        runTest {
            // The same 30 seconds on both: the plank draws it as its time field, while the bench
            // press — a swapped-in slot — carries it hidden, so only the plank's is priced.
            val timed = List(2) { PlannedSet(duration = 30) }
            templates.seed("Bench", listOf(draft(exerciseId = 1L, sets = timed, restDuration = 0)))
            templates.seed("Plank", listOf(draft(exerciseId = 2L, sets = timed, restDuration = 0)))

            val (bench, plank) = summaries().first().map(TemplateSummary::estimatedDuration)

            assertEquals(TemplateDuration.WORK_PER_SET * 2, bench)
            assertEquals(60.seconds, plank)
        }
}
