package com.opengym.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opengym.core.data.di.UserPreferencesStore
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
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
 * back to defaults three times over: [retryWhen] for a store that cannot be read at all,
 * [toUserPreferences] for a value stored under a name this build does not know, and the
 * same function again for a value the model's invariants refuse. All three mean "no
 * usable answer for this setting", and the answer to that is the default — the same
 * judgement `InProgressSessionRepository.load` makes when it returns null for a corrupt
 * draft. The setters are where a bad value is *rejected*, before it can be stored; by
 * the time one is being read, refusing it helps nobody.
 *
 * Falling back is not the same as giving up, which is why the read failure is handled
 * with [retryWhen] and not the [kotlinx.coroutines.flow.catch] the androidx samples
 * reach for — see [observe].
 *
 * ### Writes, on the other hand, do throw
 *
 * `edit` propagates its [IOException], and the interface says so. A settings *read* that
 * throws takes a screen down for a value nobody asked for; a settings *write* that fails
 * silently leaves the user looking at a toggle that flipped and did not save. The two
 * asymmetric answers are the same judgement applied to different consequences.
 */
@Singleton
class UserPreferencesRepositoryImpl @Inject constructor(
    @UserPreferencesStore private val dataStore: DataStore<Preferences>,
) : UserPreferencesRepository {

    /**
     * The settings, re-read on every change and never failing at the collector.
     *
     * [retryWhen] rather than [kotlinx.coroutines.flow.catch], which emits and then
     * *completes* the flow: one transient read — a full disk, an EIO, a failed write
     * surfacing on [DataStore.data] — would end every live collection for the rest of the
     * process. The interface promises "re-emitted on every change", and the app-root theme
     * observer is subscribed for the whole process lifetime, so completing the flow means
     * later writes land on disk with nothing subscribed left to publish them: settings
     * appear to save and nothing moves until the next launch.
     *
     * The delay before retrying is what stops a store that is *permanently* unreadable
     * from spinning the IO pool for as long as a screen is open.
     */
    override fun observe(): Flow<UserPreferences> = dataStore.data
        .retryWhen { cause, attempt ->
            // Only IO: a bug in the mapping below is not something to paper over with a
            // screen full of defaults.
            if (cause !is IOException) return@retryWhen false
            // Defaults once, so a collector that has not been handed anything yet still
            // gets its first frame. Past that, retrying in the background is the better
            // answer than flashing defaults over a value that was good a moment ago.
            if (attempt == 0L) emit(emptyPreferences())
            delay(RETRY_DELAY)
            true
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
        // is a setting that silently does not take. This is exactly [UserPreferences]' own
        // invariant read through the truncation, so every legal model value round-trips
        // through here and nothing this accepts can produce one the model refuses.
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

    private companion object {

        /**
         * How long [observe] waits before re-reading a store that failed.
         *
         * Long enough that a store which is never going to be readable costs a wakeup a
         * second rather than a busy loop, short enough that the recoverable case — the
         * disk that had no space for a moment — is over before a user has finished
         * noticing. A backoff would buy nothing here: this flow lives as long as the
         * screen collecting it, and a fixed second is already below that.
         */
        val RETRY_DELAY: Duration = 1.seconds
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
