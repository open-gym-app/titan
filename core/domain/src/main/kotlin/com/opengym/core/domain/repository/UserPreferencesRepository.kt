package com.opengym.core.domain.repository

import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.plate.PlateInventory
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlin.time.Duration

/**
 * The user's settings, durable across process death and observable while the app runs.
 *
 * ### Read whole, write one at a time
 *
 * [observe] hands out the entire [UserPreferences]; every writer names a single setting.
 * The asymmetry is deliberate and is what makes the last acceptance criterion of M4-16
 * hold: a `save(preferences)` taking the whole object is a read-modify-write, and two
 * screens doing that concurrently means whichever writes second silently reverts the
 * other's change. A targeted setter cannot clobber a setting it does not name.
 *
 * ### Emission contract
 *
 * The same one [SessionStateRepository] carries, and for the same reason — a settings
 * flow that only emits on change is unusable as the source of a screen's first frame:
 *
 * - **Conflated and latest-wins.** A slow collector sees the newest value, not a backlog
 *   of every intermediate one, and never a value older than one it has already seen.
 * - **Current value on subscribe.** A new collector is given the settings as they stand,
 *   immediately, without a write having to happen first — so `observe().first()` returns
 *   on a fresh install rather than suspending forever.
 * - **Never empty, never failing.** [UserPreferences] has a default for every field, and
 *   the flow falls back to it: nothing stored yet, a value written by a build that meant
 *   something else by it, a store that cannot be read at all. A settings read is on the
 *   path to the first frame of every screen, so it must not be the thing that throws.
 *
 * ### Writing can fail, and says so
 *
 * Every setter throws [IOException] when the store cannot be written — no space, a
 * read-only filesystem, a file that cannot be replaced. That is the deliberate opposite
 * of the read contract above, and the asymmetry is the consequences, not an oversight: a
 * read that throws takes down a screen over a value the user never asked about, while a
 * write that is swallowed leaves them looking at a control that moved and did not save.
 * So a caller doing `viewModelScope.launch { setTheme(DARK) }` has to decide what the
 * user sees when it fails; it is not a call that can be made and forgotten.
 *
 * ### Where the implementation lives
 *
 * `:core:data`. Nothing about the storage medium is decided here — no key names, no file,
 * no DataStore — only the values and the guarantees above.
 */
interface UserPreferencesRepository {

    /** The current settings, re-emitted on every change. Never empty; see the emission contract. */
    fun observe(): Flow<UserPreferences>

    /**
     * Sets the unit system weights and distances are displayed in.
     *
     * @throws IOException if the settings cannot be written.
     */
    suspend fun setUnitSystem(unitSystem: UnitSystem)

    /**
     * Sets the colour scheme.
     *
     * @throws IOException if the settings cannot be written.
     */
    suspend fun setTheme(theme: ThemePreference)

    /**
     * Sets the rest a set gets by default, to whole-second resolution — anything finer is
     * truncated, which is the granularity a rest is set in anyway.
     *
     * @throws IllegalArgumentException if [duration] is infinite, or is under one whole
     * second. Unlike the enum-valued settings, this one can be handed a value no user
     * could have meant, and storing it would leave every later read falling back to the
     * default with no indication of why — a setting that silently does not take. Every
     * value [UserPreferences.defaultRestDuration] accepts is accepted here unchanged.
     * @throws IOException if the settings cannot be written.
     */
    suspend fun setDefaultRestDuration(duration: Duration)

    /**
     * Sets the empty barbell's weight, in milligrams — see [UserPreferences.barWeight].
     *
     * @throws IllegalArgumentException if [barWeight] is not positive.
     * @throws IOException if the settings cannot be written.
     */
    suspend fun setBarWeight(barWeight: Long)

    /**
     * Records the plates the user can reach, or **null** to go back to the standard rack for
     * their unit system — see [UserPreferences.plateInventory].
     *
     * Null is a real value here rather than a way of saying "leave it alone": the inventory
     * editor's "use the standard set" is exactly this call, and it has to *forget* the stored
     * rack, not overwrite it with a copy of today's default that would then stop following a
     * change of unit system.
     *
     * @throws IOException if the settings cannot be written.
     */
    suspend fun setPlateInventory(inventory: PlateInventory?)

    /**
     * Records how many pairs of one plate size the user has, leaving every other size alone.
     *
     * This is [setPlateInventory]'s whole-rack write reduced to the one thing the inventory
     * editor's steppers actually change, and it exists for the reason the note at the top of
     * this file gives: a caller doing the read-modify-write itself — read the inventory off a
     * screen's state, copy it with one count changed, write the copy back — reverts any edit
     * that has not round-tripped through [observe] yet. Two rows changed inside the time one
     * write takes is not a contrived case; it is what setting up a rack looks like. The
     * read-modify-write still happens, but it happens inside the store's own transaction,
     * where it is serialised against every other write.
     *
     * A size the stored rack does not list is added to it. When nothing is stored yet, the
     * standard rack for the user's unit system is what gets edited — which is the rack the
     * editor was showing them.
     *
     * @throws IllegalArgumentException if [weight] is not a weight a plate may have, or
     * [pairs] is not a count one may be held in — see
     * [com.opengym.core.domain.plate.PlateStock].
     * @throws IOException if the settings cannot be written.
     */
    suspend fun setPlatePairs(
        weight: Long,
        pairs: Int,
    )

    /**
     * Sets the training goal. Recorded only; nothing reads it in v1.
     *
     * @throws IOException if the settings cannot be written.
     */
    suspend fun setTrainingGoal(trainingGoal: TrainingGoal)

    /**
     * Puts every setting back to the default a fresh install reads, in one write.
     *
     * The one operation that is not "a setter for a named setting", and it earns the
     * exception by being the only way to express *forget what you knew about me*: settings
     * are erased as part of the local wipe behind #M4-23's "delete all data", and a wipe
     * that reset five of six settings would leave the app remembering the sixth about a
     * user it has been told to forget. Resetting by calling every setter with its default
     * is that bug waiting for the seventh setting to be added.
     *
     * It resets [setOnboardingCompleted] with the rest, which is what makes the next launch
     * ask the first-run questions again — the correct behaviour for a device the user has
     * just emptied, and the reason this is not called anywhere a "reset my preferences"
     * button would be.
     *
     * @throws IOException if the settings cannot be written.
     */
    suspend fun clear()

    /**
     * Records whether onboarding has been completed.
     *
     * Writing `true` is what stops the next launch routing back into onboarding, so it
     * belongs at the *end* of the flow, once the answers are saved — not when it starts.
     * It is also the setter whose [IOException] matters most: onboarding that cannot
     * record its own completion has to say so rather than move on, or the next launch
     * asks the same questions again.
     *
     * @throws IOException if the settings cannot be written.
     */
    suspend fun setOnboardingCompleted(completed: Boolean)
}
