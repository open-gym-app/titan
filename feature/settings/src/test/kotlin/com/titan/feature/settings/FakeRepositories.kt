package com.titan.feature.settings

import com.titan.core.domain.model.DataFootprint
import com.titan.core.domain.model.DefaultTemplate
import com.titan.core.domain.model.DefaultTemplateName
import com.titan.core.domain.model.FitnessLevel
import com.titan.core.domain.model.OneRepMaxFormula
import com.titan.core.domain.model.RepRange
import com.titan.core.domain.model.TemplateCategory
import com.titan.core.domain.model.TemplateExercise
import com.titan.core.domain.model.TemplateExerciseDraft
import com.titan.core.domain.model.ThemePreference
import com.titan.core.domain.model.TrainingGoal
import com.titan.core.domain.model.UnitSystem
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.model.WorkoutTemplate
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationPermission
import com.titan.core.domain.plate.PlateInventory
import com.titan.core.domain.plate.PlateStock
import com.titan.core.domain.repository.DefaultTemplateCatalogue
import com.titan.core.domain.repository.LocalDataRepository
import com.titan.core.domain.repository.TemplateRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.time.Duration

/**
 * In-memory stand-ins for the two repositories the settings ViewModel talks to.
 *
 * Hand-written rather than mocked, because both contracts are about *behaviour over time* —
 * a flow that re-emits after a write is the thing under test in half the cases below, and a
 * mock returning a fixed flow cannot express it. They honour the real contracts: the
 * preference flow emits the current value on subscribe and again on every change, and the
 * setters validate exactly what the real ones validate.
 */
class FakeUserPreferencesRepository(initial: UserPreferences = UserPreferences()) : UserPreferencesRepository {

    private val preferences = MutableStateFlow(initial)

    /** When set, every write throws it — the store that cannot be written. */
    var writeFailure: IOException? = null

    override fun observe(): Flow<UserPreferences> = preferences

    override suspend fun setUnitSystem(unitSystem: UnitSystem) = write { it.copy(unitSystem = unitSystem) }

    override suspend fun setTheme(theme: ThemePreference) = write { it.copy(theme = theme) }

    override suspend fun setDefaultRestDuration(duration: Duration) {
        require(duration.isFinite() && duration.inWholeSeconds > 0) { "invalid rest: $duration" }
        write { it.copy(defaultRestDuration = duration) }
    }

    override suspend fun setBarWeight(barWeight: Long) {
        require(barWeight > 0) { "invalid bar weight: $barWeight" }
        write { it.copy(barWeight = barWeight) }
    }

    override suspend fun setPlateInventory(inventory: PlateInventory?) = write { it.copy(plateInventory = inventory) }

    /** The rack edited inside the update, the way the real one edits inside `edit`. */
    override suspend fun setPlatePairs(
        weight: Long,
        pairs: Int,
    ) {
        val edited = PlateStock(weight = weight, pairs = pairs)
        write { it.copy(plateInventory = it.plates.withPairs(edited.weight, edited.pairs)) }
    }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) = write { it.copy(trainingGoal = trainingGoal) }

    override suspend fun setFitnessLevel(fitnessLevel: FitnessLevel) = write { it.copy(fitnessLevel = fitnessLevel) }

    override suspend fun setCustomRepRange(range: RepRange) = write { it.copy(customRepRange = range) }

    override suspend fun setCustomIncrement(milligrams: Long?) = write { it.copy(customIncrement = milligrams) }

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) = write { it.copy(oneRepMaxFormula = formula) }

    override suspend fun setWarmupSuggestions(enabled: Boolean) = write { it.copy(warmupSuggestions = enabled) }

    override suspend fun setOnboardingCompleted(completed: Boolean) = write { it.copy(onboardingCompleted = completed) }

    override suspend fun setNotificationEnabled(
        kind: NotificationKind,
        enabled: Boolean,
    ) = write { it.copy(notifications = it.notifications.with(kind, enabled)) }

    override suspend fun setNotificationPermissionRequested() =
        write { it.copy(notifications = it.notifications.copy(permissionRequested = true)) }

    override suspend fun setReminderDay(
        day: DayOfWeek,
        enabled: Boolean,
    ) = write {
        val days = it.reminders.days
        it.copy(reminders = it.reminders.copy(days = if (enabled) days + day else days - day))
    }

    override suspend fun setReminderTime(time: LocalTime) =
        write { it.copy(reminders = it.reminders.copy(timeOfDay = time)) }

    override suspend fun clear() = write { UserPreferences() }

    private fun write(transform: (UserPreferences) -> UserPreferences) {
        writeFailure?.let { throw it }
        preferences.update(transform)
    }
}

class FakeLocalDataRepository(initial: DataFootprint = DataFootprint()) : LocalDataRepository {

    private val footprint = MutableStateFlow(initial)

    var deleteCount: Int = 0
        private set

    /** When set, [deleteAll] throws it — the wipe that could not finish. */
    var deleteFailure: Throwable? = null

    override fun observeFootprint(): Flow<DataFootprint> = footprint

    override suspend fun deleteAll() {
        deleteCount++
        deleteFailure?.let { throw it }
        footprint.value = DataFootprint()
    }
}

/**
 * The system's answer about notifications, under the test's control.
 *
 * A class rather than a lambda because the answer has to be able to *change* mid-test: the
 * whole point of the permission plumbing is that a user can leave for system settings and come
 * back with a different answer, and the recheck event is what is being tested. [allowed] is
 * therefore a `var`, and every read goes through the same method the real one does.
 */
class FakeNotificationPermission(
    var allowed: Boolean = false,
    override val isRuntimePermissionRequired: Boolean = true,
) : NotificationPermission {

    override fun areNotificationsAllowed(): Boolean = allowed
}

/**
 * Templates by name, which is all the restore row reads of them. The exercises are not kept:
 * nothing on this screen draws a template's body.
 */
class FakeTemplateRepository(vararg names: String) : TemplateRepository {

    private val templates = MutableStateFlow(names.mapIndexed { index, name -> WorkoutTemplate(index + 1L, name) })

    /** When set, [insert] throws it — a store that cannot be written. */
    var insertFailure: Throwable? = null

    val names: List<String> get() = templates.value.map(WorkoutTemplate::name)

    override fun getAll(): Flow<List<WorkoutTemplate>> = templates.map { it.sortedBy(WorkoutTemplate::name) }

    override fun getById(id: Long): Flow<WorkoutTemplate?> = templates.map { all -> all.find { it.id == id } }

    override fun getExercises(templateId: Long): Flow<List<TemplateExercise>> = MutableStateFlow(emptyList())

    override fun getAllExercises(): Flow<List<TemplateExercise>> = MutableStateFlow(emptyList())

    override suspend fun insert(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ): Long {
        insertFailure?.let { throw it }
        val id = (templates.value.maxOfOrNull(WorkoutTemplate::id) ?: 0L) + 1
        templates.update { it + template.copy(id = id) }
        return id
    }

    override suspend fun update(
        template: WorkoutTemplate,
        exercises: List<TemplateExerciseDraft>,
    ) = templates.update { all -> all.map { if (it.id == template.id) template else it } }

    override suspend fun delete(id: Long) = templates.update { all -> all.filterNot { it.id == id } }
}

/** The defaults, as plain names over one exercise each. */
class FakeDefaultTemplateCatalogue(vararg names: String) : DefaultTemplateCatalogue {

    private val defaults = names.map { name ->
        DefaultTemplate(
            name = DefaultTemplateName(name),
            category = TemplateCategory.GENERAL,
            exercises = listOf(TemplateExerciseDraft(exerciseId = 1L)),
        )
    }

    /** When set, both reads throw it — a catalogue that cannot be read. */
    var readFailure: Throwable? = null

    override suspend fun getNames(): List<DefaultTemplateName> {
        readFailure?.let { throw it }
        return defaults.map { it.name }
    }

    override suspend fun getAll(): List<DefaultTemplate> {
        readFailure?.let { throw it }
        return defaults
    }
}
