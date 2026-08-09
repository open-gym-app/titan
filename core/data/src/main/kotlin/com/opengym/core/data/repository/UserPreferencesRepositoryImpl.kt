package com.opengym.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The user's settings, in Preferences DataStore.
 *
 * ### Preferences DataStore rather than Proto
 *
 * Both satisfy the contract; the deciding argument is what each costs when the store's
 * shape changes, and settings change shape constantly — six today, and the milestone
 * plan already implies more.
 *
 * - **Adding a setting is a key and a default, not a schema version.** Proto DataStore
 *   would put a `.proto` file, the protobuf plugin and a generated-source directory in
 *   the build for a document with six scalar fields. The typed-document benefit it buys
 *   is largely spent again here anyway: [UserPreferences] is the typed document, and it
 *   is declared in `:core:domain` where the storage layer cannot reach it — so a mapping
 *   step exists either way.
 * - **A key that cannot be read costs one setting, not all six.** A single serialized
 *   document is all-or-nothing: one unparseable field and the whole thing is replaced by
 *   the corruption handler, taking the five valid settings with it. Preferences degrade
 *   per key, and [toUserPreferences] takes that literally — see below.
 * - **Targeted writes are native.** [DataStore.updateData] over a document is a
 *   read-modify-write of the whole thing; `edit` over a key set touches one entry. Both
 *   are serialised by DataStore, so neither clobbers, but only one of them makes it
 *   *impossible* for a new setter to clobber a sibling by forgetting to copy it through.
 *
 * The corruption handler is still wired up (see `UserPreferencesModule`) for the case
 * Preferences cannot recover from — an unreadable file rather than an unreadable value —
 * where the only options are defaults or a crash on the way to the first frame.
 *
 * ### The emission contract, term by term
 *
 * [DataStore.data] reads the file and emits its contents to every collector, so a new
 * collector gets the current settings without a write having to happen — the criterion
 * the interface spells out. The two operators on top of it supply the rest:
 * [distinctUntilChanged] makes a write that changes nothing publish nothing, and
 * [conflate] makes a slow collector see the newest settings rather than a queue of every
 * intermediate one. Together they are `StateFlow` semantics over a file, which is what
 * `SessionStateRepository` gets for free from `MutableStateFlow` and what this contract
 * was written against.
 *
 * ### Nothing here throws at a collector
 *
 * A settings read is on the path to the first frame of every screen, so the flow falls
 * back to defaults three times over: [catch] for a store that cannot be read at all,
 * [toUserPreferences] for a value stored under a name this build does not know, and the
 * same function again for a value the model's invariants refuse. All three mean "no
 * usable answer for this setting", and the answer to that is the default — the same
 * judgement `InProgressSessionRepository.load` makes when it returns null for a corrupt
 * draft. The setters are where a bad value is *rejected*, before it can be stored; by
 * the time one is being read, refusing it helps nobody.
 */
@Singleton
class UserPreferencesRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UserPreferencesRepository {

    override fun observe(): Flow<UserPreferences> = dataStore.data
        .catch { cause ->
            // Only IO: a bug in the mapping below is not something to paper over with a
            // screen full of defaults.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map(::toUserPreferences)
        .distinctUntilChanged()
        .conflate()

    override suspend fun setUnitSystem(unitSystem: UnitSystem) {
        dataStore.edit { it[UserPreferencesKeys.UNIT_SYSTEM] = unitSystem.name }
    }

    override suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { it[UserPreferencesKeys.THEME] = theme.name }
    }

    override suspend fun setDefaultRestDuration(duration: Duration) {
        // Whole seconds, not merely positive: the store's resolution is a second, so 500ms
        // would truncate to zero — a value the reader then discards for the default, which
        // is a setting that silently does not take.
        require(duration.isFinite() && duration.inWholeSeconds > 0) {
            "the default rest duration must be at least one whole second and finite, was $duration"
        }
        dataStore.edit { it[UserPreferencesKeys.REST_DURATION_SECONDS] = duration.inWholeSeconds }
    }

    override suspend fun setBarWeight(barWeight: Long) {
        require(barWeight > 0) { "the bar weight must be positive, was $barWeight mg" }
        dataStore.edit { it[UserPreferencesKeys.BAR_WEIGHT] = barWeight }
    }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) {
        dataStore.edit { it[UserPreferencesKeys.TRAINING_GOAL] = trainingGoal.name }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[UserPreferencesKeys.ONBOARDING_COMPLETED] = completed }
    }
}

/**
 * The stored keys.
 *
 * The names are a persisted format: renaming one silently resets that setting for every
 * existing install, which is why they are string literals here rather than derived from a
 * property or an enum that a refactor would happily rename. The *values* of the three
 * enum-typed keys are persisted by `name` for the same reason, and
 * [com.opengym.core.domain.model.UserPreferences]' own test pins those names.
 *
 * The seconds/milligrams suffixes are on the keys that hold a bare number, so a stored
 * file is legible without this class next to it.
 */
private object UserPreferencesKeys {
    val UNIT_SYSTEM = stringPreferencesKey("unit_system")
    val THEME = stringPreferencesKey("theme")
    val REST_DURATION_SECONDS = longPreferencesKey("default_rest_duration_seconds")
    val BAR_WEIGHT = longPreferencesKey("bar_weight_milligrams")
    val TRAINING_GOAL = stringPreferencesKey("training_goal")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
}

/**
 * The stored keys as a [UserPreferences], with the default standing in wherever the store
 * has no usable answer.
 *
 * "No usable answer" is three cases and they are handled identically: the key is absent
 * (a fresh install, or a setting never changed), the key holds an enum name this build
 * does not have (a value written by a newer build, or one since renamed), or the key
 * holds a number the model refuses (a non-positive rest or bar weight, which no setter
 * would have written). The last is the only one that would otherwise throw, and it would
 * throw from inside a `map` on a flow a screen is collecting.
 */
private fun toUserPreferences(stored: Preferences): UserPreferences {
    val defaults = UserPreferences()
    return UserPreferences(
        unitSystem = stored[UserPreferencesKeys.UNIT_SYSTEM].toEnumOrDefault(defaults.unitSystem),
        theme = stored[UserPreferencesKeys.THEME].toEnumOrDefault(defaults.theme),
        defaultRestDuration = stored[UserPreferencesKeys.REST_DURATION_SECONDS]
            ?.seconds
            ?.takeIf { it > Duration.ZERO && it.isFinite() }
            ?: defaults.defaultRestDuration,
        barWeight = stored[UserPreferencesKeys.BAR_WEIGHT]?.takeIf { it > 0 } ?: defaults.barWeight,
        trainingGoal = stored[UserPreferencesKeys.TRAINING_GOAL].toEnumOrDefault(defaults.trainingGoal),
        onboardingCompleted = stored[UserPreferencesKeys.ONBOARDING_COMPLETED] ?: defaults.onboardingCompleted,
    )
}

/**
 * The entry named by this string, or [default] when it names nothing.
 *
 * `enumValueOf` throws on an unknown name, and an unknown name is an ordinary thing for a
 * store to hold — a user who downgrades after picking a value a later build added has one
 * sitting in their file.
 */
private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
    this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
