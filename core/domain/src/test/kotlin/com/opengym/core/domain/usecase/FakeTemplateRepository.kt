package com.opengym.core.domain.usecase

import com.opengym.core.domain.model.TemplateExercise
import com.opengym.core.domain.model.TemplateExerciseDraft
import com.opengym.core.domain.model.WorkoutTemplate
import com.opengym.core.domain.repository.TemplateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [TemplateRepository], hand-written for the same reason as
 * [FakeSessionRepository]: these tests assert on what the store ends up holding
 * after a write, which is shorter and stricter than stubbing each call in turn.
 *
 * It honours the parts of the contract the use cases lean on — name ordering,
 * position numbering from list order, cascade on delete — so a test that passes here
 * is testing the rule and not the fake.
 */
class FakeTemplateRepository : TemplateRepository {

    private val templates = MutableStateFlow<Map<Long, WorkoutTemplate>>(emptyMap())
    private val exercises = MutableStateFlow<Map<Long, List<TemplateExercise>>>(emptyMap())
    private var nextTemplateId = 1L
    private var nextExerciseId = 1L

    /** Writes seen, so a test can pin down that a refused call wrote nothing. */
    var insertCount = 0
        private set
    var updateCount = 0
        private set
    var deleteCount = 0
        private set

    /** Total writes of any kind — the assertion for "this was a pure read". */
    val writeCount: Int get() = insertCount + updateCount + deleteCount

    /**
     * Whether [getExercises] honours the ordering its contract promises.
     *
     * Set it false to have the fake hand its list back out of position order on purpose.
     * [ApplyTemplateUseCase] re-sorts rather than trusting the repository, precisely so an
     * implementation that stopped ordering could not silently reorder a user's workout —
     * and that defence is only testable against a store that breaks the promise, because
     * one that keeps it makes the sort a no-op.
     */
    var honoursPositionOrder = true

    override fun getAll(): Flow<List<WorkoutTemplate>> =
        templates.map { stored -> stored.values.sortedBy(WorkoutTemplate::name) }

    override fun getById(id: Long): Flow<WorkoutTemplate?> = templates.map { it[id] }

    override fun getExercises(templateId: Long): Flow<List<TemplateExercise>> =
        exercises.map { stored ->
            val list = stored[templateId].orEmpty()
            if (honoursPositionOrder) list else list.reversed()
        }

    override suspend fun insert(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ): Long {
        insertCount++
        return put(template, exercises)
    }

    override suspend fun update(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ) {
        updateCount++
        templates.value += template.id to template
        store(template.id, exercises)
    }

    override suspend fun delete(id: Long) {
        deleteCount++
        templates.value -= id
        // Mirrors the ON DELETE CASCADE on template_exercise.template_id.
        exercises.value -= id
    }

    /** Stores [template] under a fresh id with [exercises], returning that id. */
    private fun put(template: WorkoutTemplate, exercises: List<TemplateExerciseDraft>): Long {
        val id = nextTemplateId++
        templates.value += id to template.copy(id = id)
        store(id, exercises)
        return id
    }

    /** Replaces [templateId]'s list, numbering positions from the order given. */
    private fun store(templateId: Long, drafts: List<TemplateExerciseDraft>) {
        exercises.value += templateId to drafts.mapIndexed { position, draft ->
            TemplateExercise(
                id = nextExerciseId++,
                templateId = templateId,
                exerciseId = draft.exerciseId,
                position = position,
                defaultSets = draft.defaultSets,
                defaultReps = draft.defaultReps,
                defaultWeight = draft.defaultWeight,
                restDuration = draft.restDuration,
                notes = draft.notes,
            )
        }
    }

    /** Direct reads, bypassing the Flows, for assertions. */
    fun stored(id: Long): WorkoutTemplate? = templates.value[id]

    fun storedExercises(templateId: Long): List<TemplateExercise> =
        exercises.value[templateId].orEmpty()

    fun storedCount(): Int = templates.value.size

    /**
     * Seeds a template straight into the store without counting as a write, so the
     * write assertions only see what the use case under test did.
     */
    fun seed(
        name: String,
        drafts: List<TemplateExerciseDraft> = listOf(draft(exerciseId = 1L)),
        icon: String? = null,
    ): Long = put(WorkoutTemplate(name = name, icon = icon), drafts)

    /** Seeds a template whose exercises were removed underneath the use cases. */
    fun seedEmpty(name: String): Long = seed(name, drafts = emptyList())
}

/** Terse [TemplateExerciseDraft] for tests that only care about identity and order. */
fun draft(
    exerciseId: Long,
    defaultSets: Int? = null,
    defaultReps: Int? = null,
    defaultWeight: Long? = null,
    restDuration: Long? = null,
    notes: String? = null,
): TemplateExerciseDraft = TemplateExerciseDraft(
    exerciseId = exerciseId,
    defaultSets = defaultSets,
    defaultReps = defaultReps,
    defaultWeight = defaultWeight,
    restDuration = restDuration,
    notes = notes,
)
