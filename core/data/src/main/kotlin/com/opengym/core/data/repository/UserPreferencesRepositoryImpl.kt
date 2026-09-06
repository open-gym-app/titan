package com.opengym.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opengym.core.data.di.UserPreferencesStore
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.NotificationPreferences
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.plate.PlateStock
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
    @param:UserPreferencesStore private val dataStore: DataStore<Preferences>,
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
    override fun observe(): Flow<UserPreferences> =
        dataStore.data
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

    /**
     * Stores the rack, or removes the key entirely for null.
     *
     * `remove` rather than writing an empty string, because the two mean different things to
     * [toUserPreferences]: an absent key is "never been asked", which derives the standard rack
     * from the unit system, while an empty inventory is the user telling us they have no plates
     * at all. Writing a blank to mean the first would make the second unsayable.
     */
    override suspend fun setPlateInventory(inventory: PlateInventory?) {
        dataStore.edit { preferences ->
            if (inventory == null) {
                preferences.remove(UserPreferencesKeys.PLATE_INVENTORY)
            } else {
                preferences[UserPreferencesKeys.PLATE_INVENTORY] = inventory.encode()
            }
        }
    }

    /**
     * Changes one size's count inside the store's own transaction.
     *
     * The read half is [Preferences] as `edit` handed it over, not [observe]'s latest emission,
     * and that is the entire point of the method — `edit` serialises against every other write
     * to this store, so two steppers pressed inside one write's round trip compose instead of
     * the second reverting the first. The same reason `setBarWeight` is a setter and not a
     * `save(preferences)`; see the interface.
     *
     * [PlateStock] is built before the edit so a weight or a count the model refuses is rejected
     * without opening a transaction — the file's rule that setters reject and readers fall back.
     */
    override suspend fun setPlatePairs(
        weight: Long,
        pairs: Int,
    ) {
        val edited = PlateStock(weight = weight, pairs = pairs)
        dataStore.edit { preferences ->
            // Through the same type-checked read the mapper uses: `Preferences.get` casts, and
            // an entry of the wrong type here would throw out of the middle of a transaction
            // rather than degrading to the default the way every read in this file does.
            val values = preferences.asMap()
            val stored = values.valueOrNull(UserPreferencesKeys.PLATE_INVENTORY)?.toPlateInventoryOrNull()
            // The rack the editor was showing them: an unset key draws the standard set, so an
            // edit against it has to start from that same set rather than from an empty rack.
            val current = stored ?: PlateInventory.defaultFor(
                values.valueOrNull(UserPreferencesKeys.UNIT_SYSTEM).toEnumOrDefault(UserPreferences().unitSystem),
            )
            preferences[UserPreferencesKeys.PLATE_INVENTORY] =
                current.withPairs(edited.weight, edited.pairs).encode()
        }
    }

    override suspend fun setTrainingGoal(trainingGoal: TrainingGoal) {
        dataStore.edit { it[UserPreferencesKeys.TRAINING_GOAL] = trainingGoal.name }
    }

    override suspend fun setOneRepMaxFormula(formula: OneRepMaxFormula) {
        dataStore.edit { it[UserPreferencesKeys.ONE_REP_MAX_FORMULA] = formula.name }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[UserPreferencesKeys.ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setNotificationEnabled(
        kind: NotificationKind,
        enabled: Boolean,
    ) {
        dataStore.edit { it[UserPreferencesKeys.notificationKey(kind)] = enabled }
    }

    override suspend fun setNotificationPermissionRequested() {
        dataStore.edit { it[UserPreferencesKeys.NOTIFICATION_PERMISSION_REQUESTED] = true }
    }

    /**
     * Empties the store, which is how every setting returns to its default.
     *
     * `clear()` rather than writing each default back, and the difference is what happens to
     * a key this build does not know about — one written by a newer version the user
     * downgraded from, or one since renamed. Writing defaults leaves those behind, so the
     * setting they encode survives a wipe that told the user everything was erased. Removing
     * every entry is the only version of this that stays true as the store grows, and it
     * needs no maintenance when a seventh setting is added.
     *
     * [toUserPreferences] then reads an empty store exactly as it reads a fresh install,
     * because to it they are the same thing.
     */
    override suspend fun clear() {
        dataStore.edit { it.clear() }
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
    val PLATE_INVENTORY = stringPreferencesKey("plate_inventory_milligrams")
    val TRAINING_GOAL = stringPreferencesKey("training_goal")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val ONE_REP_MAX_FORMULA = stringPreferencesKey("one_rep_max_formula")
    val NOTIFICATION_PERMISSION_REQUESTED = booleanPreferencesKey("notification_permission_requested")

    /**
     * One key per notification kind, spelled out rather than derived from the enum.
     *
     * `booleanPreferencesKey("notifications_" + kind.name.lowercase())` would be shorter and
     * would rename every user's stored flag the day somebody renames an entry — the same trap
     * the note above describes, made worse by being invisible at the rename site. The `when`
     * is exhaustive, so a fifth kind is a compile error here rather than a setting that reads
     * as off however often it is switched on.
     */
    fun notificationKey(kind: NotificationKind) =
        when (kind) {
            NotificationKind.REMINDERS -> booleanPreferencesKey("notifications_reminders")
            NotificationKind.REST_TIMER -> booleanPreferencesKey("notifications_rest_timer")
            NotificationKind.SESSION -> booleanPreferencesKey("notifications_session")
            NotificationKind.PROGRESSION -> booleanPreferencesKey("notifications_progression")
        }
}

/**
 * The stored keys as a [UserPreferences], with the default standing in wherever the store
 * has no usable answer.
 *
 * "No usable answer" is four cases and they are handled identically: the key is absent
 * (a fresh install, or a setting never changed), the key holds an enum name this build
 * does not have (a value written by a newer build, or one since renamed), the key holds a
 * number the model refuses (a non-positive rest or bar weight, which no setter would have
 * written), or the key holds a value of the wrong *type* altogether — a build that stored
 * a flag as text, or a hand-edited file. The last two are the ones that would otherwise
 * throw, and they would throw from inside a `map` on a flow a screen is collecting.
 *
 * Every read below therefore goes through [valueOrNull] rather than `Preferences.get`,
 * which casts the stored value to the key's type and throws [ClassCastException] when it
 * is something else. That exception is not an [IOException], so `observe`'s `retryWhen`
 * would not catch it either: one mistyped entry would take down every screen in the app
 * until the file was deleted.
 */
private fun toUserPreferences(stored: Preferences): UserPreferences {
    val defaults = UserPreferences()
    // `asMap()` builds a fresh map every call, so it is taken once for the dozen reads below
    // rather than once per read: this runs on every emission of a flow the app root and every
    // screen collect, and per-key copying makes a linear lookup quadratic in the store's size.
    val values = stored.asMap()
    return UserPreferences(
        unitSystem = values.valueOrNull(UserPreferencesKeys.UNIT_SYSTEM).toEnumOrDefault(defaults.unitSystem),
        theme = values.valueOrNull(UserPreferencesKeys.THEME).toEnumOrDefault(defaults.theme),
        defaultRestDuration = values.valueOrNull(UserPreferencesKeys.REST_DURATION_SECONDS)
            ?.seconds
            ?.takeIf { it > Duration.ZERO && it.isFinite() }
            ?: defaults.defaultRestDuration,
        barWeight = values.valueOrNull(UserPreferencesKeys.BAR_WEIGHT)?.takeIf { it > 0 } ?: defaults.barWeight,
        plateInventory = values.valueOrNull(UserPreferencesKeys.PLATE_INVENTORY)?.toPlateInventoryOrNull(),
        trainingGoal = values.valueOrNull(UserPreferencesKeys.TRAINING_GOAL).toEnumOrDefault(defaults.trainingGoal),
        onboardingCompleted = values.valueOrNull(UserPreferencesKeys.ONBOARDING_COMPLETED)
            ?: defaults.onboardingCompleted,
        oneRepMaxFormula = values.valueOrNull(UserPreferencesKeys.ONE_REP_MAX_FORMULA)
            .toEnumOrDefault(defaults.oneRepMaxFormula),
        notifications = values.toNotificationPreferences(defaults.notifications),
    )
}

/**
 * The value under [key], or **null** when there is none *of that type*.
 *
 * `Preferences.get` is an unchecked cast in disguise: it looks the entry up by name and hands
 * it back as the key's type, so a `boolean` key over an entry some other build wrote as text
 * throws where every caller here expects a null. See [toUserPreferences] for why throwing is
 * not survivable on this path.
 *
 * Over the map rather than over [Preferences] itself, because `Preferences.asMap` copies: the
 * caller takes the snapshot once and reads every key out of it.
 *
 * `reified`, and that is the whole of it: an unreified `as? T` over an erased type parameter
 * checks nothing at all and defers the same [ClassCastException] to whoever uses the result.
 */
private inline fun <reified T : Any> Map<Preferences.Key<*>, Any>.valueOrNull(key: Preferences.Key<T>): T? =
    this[key] as? T

/**
 * The notification flags, one key at a time, defaulting per kind.
 *
 * The per-key fallback matters more here than anywhere else in this file: the default is not
 * uniform — the rest timer starts on and the other three start off — so a whole-object
 * fallback would turn one unreadable flag into three settings reverting at once. Reading each
 * key against its own default keeps a store that is half-written readable as exactly the half
 * that was written.
 */
private fun Map<Preferences.Key<*>, Any>.toNotificationPreferences(
    defaults: NotificationPreferences,
): NotificationPreferences =
    NotificationPreferences(
        enabled = NotificationKind.entries
            .filter { kind -> valueOrNull(UserPreferencesKeys.notificationKey(kind)) ?: (kind in defaults) }
            .toSet(),
        permissionRequested = valueOrNull(UserPreferencesKeys.NOTIFICATION_PERMISSION_REQUESTED)
            ?: defaults.permissionRequested,
    )

/*
 * The plate inventory, as one line of text.
 *
 * `25000000x6;20000000x4;…` — each size's weight in milligrams, an `x`, and how many pairs of
 * it, heaviest first. Not JSON, and not a second DataStore file: the value is a short list of
 * pairs of integers, and the format above is legible in the stored file exactly the way the
 * `_milligrams` and `_seconds` key suffixes are meant to keep the rest of it legible. A
 * serialized document would also make the whole rack unreadable over one bad character, where
 * this degrades the way every other key here does — see [toPlateInventoryOrNull].
 */
private const val PLATE_SEPARATOR = ";"
private const val PAIRS_SEPARATOR = "x"

private fun PlateInventory.encode(): String =
    stock.joinToString(PLATE_SEPARATOR) { "${it.weight}$PAIRS_SEPARATOR${it.pairs}" }

/**
 * The stored line as an inventory, or **null** when it is not one.
 *
 * Null is the same answer an absent key gives, and deliberately so: a rack that cannot be read
 * back — a truncated write, a hand-edited file, a size a later build allowed and this one does
 * not — leaves the user looking at the standard set for their unit system rather than at a
 * screen that will not draw. That is the file's rule for every other setting; see the note on
 * "no usable answer" above [toUserPreferences].
 *
 * [PlateInventory.of] rather than the constructor, so a line that survived with its sizes out
 * of order is repaired rather than discarded — the ordering invariant is there to make equality
 * meaningful, and nothing about a reordered line is ambiguous.
 */
private fun String.toPlateInventoryOrNull(): PlateInventory? =
    try {
        PlateInventory.of(
            split(PLATE_SEPARATOR)
                .filter { it.isNotEmpty() }
                .map { entry ->
                    val parts = entry.split(PAIRS_SEPARATOR)
                    require(parts.size == 2) { "a plate entry must be <milligrams>x<pairs>, was $entry" }
                    PlateStock(weight = parts[0].toLong(), pairs = parts[1].toInt())
                },
        )
    } catch (_: IllegalArgumentException) {
        // Covers all three ways a line goes bad: `toLong`/`toInt` throw `NumberFormatException`,
        // the shape check above throws directly, and `PlateStock`/`PlateInventory` throw on a
        // weight or a count outside what the model admits.
        null
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
