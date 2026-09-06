package com.opengym.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opengym.core.domain.model.OneRepMaxFormula
import com.opengym.core.domain.model.ThemePreference
import com.opengym.core.domain.model.TrainingGoal
import com.opengym.core.domain.model.UnitSystem
import com.opengym.core.domain.model.UserPreferences
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.NotificationPreferences
import com.opengym.core.domain.plate.PlateInventory
import com.opengym.core.domain.plate.PlateStock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [UserPreferencesRepositoryImpl] against a real Preferences DataStore over a real file.
 *
 * Not a fake store: three of the criteria covered here — surviving process death,
 * defaults on a fresh install, and concurrent writes not clobbering each other — are
 * properties of DataStore's own file handling, and a fake would only assert that the fake
 * has them. The one place a double *is* used is the unreadable-store pair at the bottom,
 * where the failure being simulated cannot be provoked from a temporary folder.
 *
 * ### The process-death rig
 *
 * [withRawStore] builds a store over [storeFile], runs a block against it, then cancels
 * the store's scope and waits for it to finish. That teardown is the whole trick:
 * DataStore caches what it has read in memory and holds the file until its scope
 * completes, so a second call over the same path is a genuinely cold read of what is on
 * disk — which is what a relaunched process does. It also has to happen, since DataStore
 * refuses two live instances over one file.
 */
class UserPreferencesRepositoryImplTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** The one file every store in a test shares, so a second store reads the first's writes. */
    private val storeFile: File get() = File(temporaryFolder.root, "user-preferences.preferences_pb")

    @Test
    fun `a fresh install reads the defaults, with nothing ever written`() =
        withStore { repository ->
            val preferences = repository.observe().first()

            assertEquals(UnitSystem.METRIC, preferences.unitSystem)
            assertEquals(ThemePreference.SYSTEM, preferences.theme)
            assertEquals(90.seconds, preferences.defaultRestDuration)
            assertEquals(20_000_000L, preferences.barWeight)
            assertEquals(TrainingGoal.STRENGTH, preferences.trainingGoal)
            assertEquals(OneRepMaxFormula.EPLEY, preferences.oneRepMaxFormula)
            assertFalse(preferences.onboardingCompleted)
            // Null, not the metric rack: the derived `plates` is what follows the unit system,
            // and it can only do that while nothing has been stored. See `UserPreferences`.
            assertNull(preferences.plateInventory)
        }

    @Test
    fun `each setter writes its own preference and reads it back`() =
        withStore { repository ->
            repository.setUnitSystem(UnitSystem.IMPERIAL)
            assertEquals(UnitSystem.IMPERIAL, repository.observe().first().unitSystem)

            repository.setTheme(ThemePreference.DARK)
            assertEquals(ThemePreference.DARK, repository.observe().first().theme)

            repository.setDefaultRestDuration(120.seconds)
            assertEquals(120.seconds, repository.observe().first().defaultRestDuration)

            repository.setBarWeight(15_000_000)
            assertEquals(15_000_000L, repository.observe().first().barWeight)

            repository.setPlateInventory(HOME_RACK)
            assertEquals(HOME_RACK, repository.observe().first().plateInventory)

            repository.setTrainingGoal(TrainingGoal.HYPERTROPHY)
            assertEquals(TrainingGoal.HYPERTROPHY, repository.observe().first().trainingGoal)

            repository.setOneRepMaxFormula(OneRepMaxFormula.BRZYCKI)
            assertEquals(OneRepMaxFormula.BRZYCKI, repository.observe().first().oneRepMaxFormula)

            repository.setOnboardingCompleted(true)
            assertTrue(repository.observe().first().onboardingCompleted)
        }

    @Test
    fun `a setter leaves every other preference alone`() =
        withStore { repository ->
            repository.setUnitSystem(UnitSystem.IMPERIAL)
            repository.setBarWeight(15_000_000)

            repository.setTheme(ThemePreference.LIGHT)

            val preferences = repository.observe().first()
            assertEquals(UnitSystem.IMPERIAL, preferences.unitSystem)
            assertEquals(15_000_000L, preferences.barWeight)
        }

    @Test
    fun `a collector that arrives after the write still sees the current value`() =
        withStore { repository ->
            // The criterion that makes observe().first() usable as a screen's first frame:
            // no write happens while this collector is subscribed.
            repository.setUnitSystem(UnitSystem.IMPERIAL)

            assertEquals(UnitSystem.IMPERIAL, repository.observe().first().unitSystem)
        }

    @Test
    fun `observe re-emits on every change`() =
        withStore { repository ->
            val seen = mutableListOf<UserPreferences>()
            val collector = launch { repository.observe().toList(seen) }
            awaitEmissions(seen, count = 1)

            repository.setTheme(ThemePreference.DARK)
            awaitEmissions(seen, count = 2)
            repository.setUnitSystem(UnitSystem.IMPERIAL)
            awaitEmissions(seen, count = 3)
            collector.cancel()

            assertEquals(
                listOf(
                    UserPreferences(),
                    UserPreferences(theme = ThemePreference.DARK),
                    UserPreferences(theme = ThemePreference.DARK, unitSystem = UnitSystem.IMPERIAL),
                ),
                seen,
            )
        }

    @Test
    fun `a write that changes nothing publishes nothing`() =
        withStore { repository ->
            // distinctUntilChanged, so the flow behaves like the StateFlow this contract was
            // written against: re-selecting the setting a user is already on is not an event.
            val seen = mutableListOf<UserPreferences>()
            val collector = launch { repository.observe().toList(seen) }
            awaitEmissions(seen, count = 1)

            repository.setTheme(ThemePreference.SYSTEM)
            quiesce()
            // A real change after it, so the assertion has something to be relative to: a
            // spurious emission from the no-op above would sit between the two below.
            repository.setUnitSystem(UnitSystem.IMPERIAL)
            awaitEmissions(seen, count = 2)
            quiesce()
            collector.cancel()

            assertEquals(
                listOf(UserPreferences(), UserPreferences(unitSystem = UnitSystem.IMPERIAL)),
                seen,
            )
        }

    @Test
    fun `concurrent writes to different preferences do not clobber each other`() =
        withStore { repository ->
            // Six writers, genuinely overlapping on the IO pool, each naming a different
            // setting. A read-modify-write of the whole document would lose five of them.
            listOf(
                async(Dispatchers.IO) { repository.setUnitSystem(UnitSystem.IMPERIAL) },
                async(Dispatchers.IO) { repository.setTheme(ThemePreference.DARK) },
                async(Dispatchers.IO) { repository.setDefaultRestDuration(45.seconds) },
                async(Dispatchers.IO) { repository.setBarWeight(15_000_000) },
                async(Dispatchers.IO) { repository.setTrainingGoal(TrainingGoal.ENDURANCE) },
                async(Dispatchers.IO) { repository.setOnboardingCompleted(true) },
            ).awaitAll()

            assertEquals(
                UserPreferences(
                    unitSystem = UnitSystem.IMPERIAL,
                    theme = ThemePreference.DARK,
                    defaultRestDuration = 45.seconds,
                    barWeight = 15_000_000,
                    trainingGoal = TrainingGoal.ENDURANCE,
                    onboardingCompleted = true,
                ),
                repository.observe().first(),
            )
        }

    @Test
    fun `concurrent writes to the same preference leave one of them stored, not a mix`() =
        withStore { repository ->
            val written = listOf(30, 60, 90, 120).map { it.seconds }

            written.map { duration -> async(Dispatchers.IO) { repository.setDefaultRestDuration(duration) } }
                .awaitAll()

            // Which one wins is a race and is not asserted; that the store holds one of
            // them, whole, is the contract.
            assertTrue(repository.observe().first().defaultRestDuration in written)
        }

    @Test
    fun `every preference survives process death`() {
        withStore { repository ->
            repository.setUnitSystem(UnitSystem.IMPERIAL)
            repository.setTheme(ThemePreference.DARK)
            repository.setDefaultRestDuration(150.seconds)
            repository.setBarWeight(15_000_000)
            repository.setPlateInventory(HOME_RACK)
            repository.setTrainingGoal(TrainingGoal.CUSTOM)
            repository.setOneRepMaxFormula(OneRepMaxFormula.BRZYCKI)
            repository.setOnboardingCompleted(true)
            repository.setNotificationEnabled(NotificationKind.REST_TIMER, false)
            repository.setNotificationEnabled(NotificationKind.PROGRESSION, true)
            repository.setNotificationPermissionRequested()
        }

        // A second store over the same file, the first one's scope finished and its
        // in-memory copy gone: everything below came off disk.
        withStore { repository ->
            assertEquals(
                UserPreferences(
                    unitSystem = UnitSystem.IMPERIAL,
                    theme = ThemePreference.DARK,
                    defaultRestDuration = 150.seconds,
                    barWeight = 15_000_000,
                    plateInventory = HOME_RACK,
                    trainingGoal = TrainingGoal.CUSTOM,
                    oneRepMaxFormula = OneRepMaxFormula.BRZYCKI,
                    onboardingCompleted = true,
                    notifications = NotificationPreferences(
                        // The rest timer switched off and the progression channel on: both
                        // the other way round from the defaults, so neither can pass by
                        // accident.
                        enabled = setOf(NotificationKind.PROGRESSION),
                        permissionRequested = true,
                    ),
                ),
                repository.observe().first(),
            )
        }
    }

    @Test
    fun `a preference written in one process is still there after another writes a different one`() {
        withStore { repository -> repository.setBarWeight(25_000_000) }
        withStore { repository -> repository.setTheme(ThemePreference.LIGHT) }

        withStore { repository ->
            val preferences = repository.observe().first()
            assertEquals(25_000_000L, preferences.barWeight)
            assertEquals(ThemePreference.LIGHT, preferences.theme)
        }
    }

    @Test
    fun `clear puts every preference back to its default`() =
        withStore { repository ->
            repository.setUnitSystem(UnitSystem.IMPERIAL)
            repository.setTheme(ThemePreference.DARK)
            repository.setDefaultRestDuration(150.seconds)
            repository.setBarWeight(15_000_000)
            repository.setPlateInventory(HOME_RACK)
            repository.setTrainingGoal(TrainingGoal.CUSTOM)
            repository.setOneRepMaxFormula(OneRepMaxFormula.BRZYCKI)
            repository.setOnboardingCompleted(true)
            repository.setNotificationEnabled(NotificationKind.REST_TIMER, false)
            repository.setNotificationEnabled(NotificationKind.SESSION, true)
            repository.setNotificationPermissionRequested()

            repository.clear()

            assertEquals(UserPreferences(), repository.observe().first())
        }

    @Test
    fun `clear removes keys this build does not know about, not just the ones it does`() =
        withRawStore { repository, store ->
            // The reason `clear` empties the store rather than writing the defaults back: a
            // key from a later build survives the second approach, so a setting the user was
            // told had been erased comes back the next time they upgrade.
            store.edit { it[stringPreferencesKey("shoe_size")] = "44" }

            repository.clear()

            assertTrue(store.data.first().asMap().isEmpty())
        }

    @Test
    fun `a cleared store stays cleared across process death`() {
        withStore { repository ->
            repository.setTheme(ThemePreference.DARK)
            repository.clear()
        }

        withStore { repository -> assertEquals(UserPreferences(), repository.observe().first()) }
    }

    @Test
    fun `an enum name this build does not know falls back to the default`() =
        withRawStore { repository, store ->
            // What a downgrade looks like: a value a later build wrote, sitting in the file.
            store.edit { it[stringPreferencesKey("unit_system")] = "NAUTICAL" }

            assertEquals(UnitSystem.METRIC, repository.observe().first().unitSystem)
        }

    @Test
    fun `a one-rep-max formula name this build does not know falls back to the default`() =
        withRawStore { repository, store ->
            store.edit { it[stringPreferencesKey("one_rep_max_formula")] = "LOMBARDI" }

            assertEquals(OneRepMaxFormula.EPLEY, repository.observe().first().oneRepMaxFormula)
        }

    @Test
    fun `a stored number the model refuses falls back to the default`() =
        withRawStore { repository, store ->
            // No setter can write these — both `require` — so this is the corrupt-file case.
            // Constructing UserPreferences from them would throw inside the flow's `map`,
            // killing a collector that is drawing a screen.
            store.edit {
                it[longPreferencesKey("default_rest_duration_seconds")] = 0
                it[longPreferencesKey("bar_weight_milligrams")] = -1
            }

            val preferences = repository.observe().first()
            assertEquals(90.seconds, preferences.defaultRestDuration)
            assertEquals(20_000_000L, preferences.barWeight)
        }

    @Test
    fun `the setters reject a value the model would refuse`() =
        withStore { repository ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.setDefaultRestDuration(Duration.ZERO) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.setDefaultRestDuration(Duration.INFINITE) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.setBarWeight(0) }
            }
            // Positive, and still not storable: seconds are the resolution, so this would go
            // in as zero and come back out as the default — a setting that did not take.
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.setDefaultRestDuration(500.milliseconds) }
            }

            // Rejected before the store was touched, so the defaults are still what is read.
            assertEquals(UserPreferences(), repository.observe().first())
        }

    @Test
    fun `a rest duration finer than a second is stored to the second`() =
        withStore { repository ->
            // Truncated, not rounded, and not rejected: only a value that would truncate to
            // nothing is refused, and 90.5s is a whole rest either way.
            repository.setDefaultRestDuration(90_500.milliseconds)

            assertEquals(90.seconds, repository.observe().first().defaultRestDuration)
        }

    // ─── The plate inventory ────────────────────────────────────────────────────────────

    @Test
    fun `a rack is stored as a line of text and read back whole`() =
        withRawStore { repository, store ->
            repository.setPlateInventory(HOME_RACK)

            // Asserted as the stored line as well as as the value, because the encoding is a
            // persisted format: changing it silently resets the rack for every existing install.
            assertEquals(
                "25000000x2;20000000x4;10000000x1",
                store.data.first()[stringPreferencesKey("plate_inventory_milligrams")],
            )
            assertEquals(HOME_RACK, repository.observe().first().plateInventory)
        }

    @Test
    fun `a rack with nothing on it is a different answer from never having been asked`() =
        withStore { repository ->
            // "I have no plates" has to be storable, or the editor's last row cannot be
            // switched off — it would spring back to the standard rack.
            repository.setPlateInventory(PlateInventory(emptyList()))

            val preferences = repository.observe().first()
            assertEquals(PlateInventory(emptyList()), preferences.plateInventory)
            assertEquals(PlateInventory(emptyList()), preferences.plates)
        }

    @Test
    fun `putting the rack back to null forgets it rather than freezing today's default`() =
        withRawStore { repository, store ->
            repository.setPlateInventory(HOME_RACK)

            repository.setPlateInventory(null)

            assertNull(store.data.first()[stringPreferencesKey("plate_inventory_milligrams")])
            // And so the derived rack follows the unit system again.
            assertEquals(
                PlateInventory.METRIC,
                repository.observe().first().plates,
            )
        }

    @Test
    fun `a rack survives process death`() {
        withStore { repository -> repository.setPlateInventory(HOME_RACK) }

        withStore { repository -> assertEquals(HOME_RACK, repository.observe().first().plateInventory) }
    }

    // ─── One size at a time ─────────────────────────────────────────────────────────────

    @Test
    fun `a size edited before anything is stored edits the standard rack`() =
        withStore { repository ->
            // The rack the editor was showing them: an unset key draws the standard set, so an edit
            // against it has to start there rather than from an empty rack.
            repository.setPlatePairs(weight = 20_000_000, pairs = 3)

            val rack = repository.observe().first().plates
            assertEquals(PlateInventory.METRIC.stock.map { it.weight }, rack.stock.map { it.weight })
            assertEquals(3, rack.stock.single { it.weight == 20_000_000L }.pairs)
        }

    @Test
    fun `a size the stored rack does not list is added to it`() =
        withStore { repository ->
            repository.setPlateInventory(HOME_RACK)

            repository.setPlatePairs(weight = 5_000_000, pairs = 2)

            assertEquals(
                listOf(25_000_000L, 20_000_000L, 10_000_000L, 5_000_000L),
                repository.observe().first().plateInventory?.stock?.map { it.weight },
            )
        }

    @Test
    fun `a weight or a count the model refuses is rejected rather than stored`() =
        withRawStore { repository, store ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.setPlatePairs(weight = 1, pairs = 2) }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.setPlatePairs(weight = 20_000_000, pairs = -1) }
            }

            // Nothing was opened, so nothing was half-written: the rack is still unasked.
            assertNull(store.data.first()[stringPreferencesKey("plate_inventory_milligrams")])
        }

    @Test
    fun `concurrent edits to different sizes compose rather than clobbering`() =
        withStore { repository ->
            // The reason this method exists at all. A caller doing the read-modify-write itself —
            // read the rack, copy it with one count changed, write the whole thing back — would keep
            // whichever of these finished last and silently revert the other three. Editing inside
            // the store's own transaction is what makes four presses four edits.
            listOf(
                async(Dispatchers.IO) { repository.setPlatePairs(20_000_000, 1) },
                async(Dispatchers.IO) { repository.setPlatePairs(10_000_000, 2) },
                async(Dispatchers.IO) { repository.setPlatePairs(5_000_000, 3) },
                async(Dispatchers.IO) { repository.setPlatePairs(2_500_000, 4) },
            ).awaitAll()

            val rack = repository.observe().first().plates
            assertEquals(1, rack.stock.single { it.weight == 20_000_000L }.pairs)
            assertEquals(2, rack.stock.single { it.weight == 10_000_000L }.pairs)
            assertEquals(3, rack.stock.single { it.weight == 5_000_000L }.pairs)
            assertEquals(4, rack.stock.single { it.weight == 2_500_000L }.pairs)
        }

    @Test
    fun `a rack that cannot be read reads as never having been asked`() =
        withRawStore { repository, store ->
            // Every way the line goes wrong is the same event to a screen: a truncated write, a
            // hand-edited file, a size some later build allowed and this one does not.
            listOf("not a rack", "25000000", "25000000x", "-1x2", "25000000xmany", "1x2").forEach { line ->
                store.edit { it[stringPreferencesKey("plate_inventory_milligrams")] = line }

                assertNull("\"$line\" should not have parsed", repository.observe().first().plateInventory)
            }
        }

    @Test
    fun `a rack stored out of order is repaired rather than discarded`() =
        withRawStore { repository, store ->
            // Nothing about a reordered line is ambiguous. The ordering invariant exists to make
            // two racks holding the same plates compare equal, not to police the file.
            store.edit { it[stringPreferencesKey("plate_inventory_milligrams")] = "10000000x1;25000000x2" }

            assertEquals(
                listOf(25_000_000L, 10_000_000L),
                repository.observe().first().plateInventory?.stock?.map { it.weight },
            )
        }

    @Test
    fun `a store that cannot be read at all emits the defaults instead of throwing`() =
        runTest {
            val repository = UserPreferencesRepositoryImpl(UnreadableDataStore)

            assertEquals(UserPreferences(), repository.observe().first())
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy(Duration)
    fun `a store that never becomes readable keeps retrying rather than ending the collection`() =
        runTest {
            // The regression that `retryWhen` exists for. `catch` emits the defaults and then
            // *completes* the flow, so this collector would be finished — and a screen holding
            // it would sit on the defaults, ignoring every later write, until the process
            // restarted. Staying subscribed is the contract; the defaults are the stopgap.
            val repository = UserPreferencesRepositoryImpl(UnreadableDataStore)
            val seen = mutableListOf<UserPreferences>()

            val collector = launch { repository.observe().toList(seen) }
            advanceTimeBy(RETRY_DELAY * RETRY_ROUNDS)
            val stillCollecting = collector.isActive
            collector.cancel()

            assertTrue("the collection ended on a read failure instead of retrying", stillCollecting)
            // Once, not once per retry: distinctUntilChanged, and the defaults are only
            // published on the first failure in the first place.
            assertEquals(listOf(UserPreferences()), seen)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy(Duration)
    fun `a store that recovers publishes again, without the collector having resubscribed`() =
        runTest {
            // The other side of the same contract: whatever the defaults stood in for, the
            // real value reaches the collector that was already there when the read failed.
            val repository = UserPreferencesRepositoryImpl(FlakyDataStore(failures = 1))
            val seen = mutableListOf<UserPreferences>()

            val collector = launch { repository.observe().toList(seen) }
            advanceTimeBy(RETRY_DELAY * RETRY_ROUNDS)
            collector.cancel()

            assertEquals(
                listOf(UserPreferences(), UserPreferences(theme = ThemePreference.DARK)),
                seen,
            )
        }

    @Test
    fun `a store that fails for a reason other than IO still throws`() =
        runTest {
            // The other half of the `retryWhen`: a mapping bug must not be swallowed into a
            // screenful of defaults — nor retried forever, which is the same failure wearing a
            // different hat — and neither would happen if the predicate caught Throwable.
            val repository = UserPreferencesRepositoryImpl(BrokenDataStore)

            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.observe().first() }
            }
        }

    // --- notifications ----------------------------------------------------

    @Test
    fun `a fresh install wants the rest timer and nothing else`() =
        withStore { repository ->
            val notifications = repository.observe().first().notifications

            // The one default-on notification, and the reason it is the exception: by the
            // time it fires, the user has already asked for it by starting a rest. See
            // `NotificationPreferences`.
            assertEquals(setOf(NotificationKind.REST_TIMER), notifications.enabled)
            // Nothing has been said to the user yet, which is what stops a fresh install
            // rendering its notification rows as blocked.
            assertFalse(notifications.permissionRequested)
        }

    @Test
    fun `each notification kind is stored on its own`() =
        withStore { repository ->
            NotificationKind.entries.forEach { kind ->
                repository.setNotificationEnabled(kind, true)
                assertTrue("$kind should be on", kind in repository.observe().first().notifications)
            }

            // And off again one at a time, leaving the others where they were — the whole
            // point of a key per kind rather than one stored set.
            repository.setNotificationEnabled(NotificationKind.SESSION, false)

            assertEquals(
                NotificationKind.entries.toSet() - NotificationKind.SESSION,
                repository.observe().first().notifications.enabled,
            )
        }

    @Test
    fun `turning the rest timer off is stored, rather than read back as its default`() =
        withStore { repository ->
            // The one kind where "off" and "never written" are different answers, and the
            // case a per-kind default gets wrong if it is applied over a stored value.
            repository.setNotificationEnabled(NotificationKind.REST_TIMER, false)

            assertFalse(NotificationKind.REST_TIMER in repository.observe().first().notifications)
        }

    @Test
    fun `a notification flag this build cannot read falls back to that kind's own default`() =
        withRawStore { repository, store ->
            // One unreadable key, not a whole unreadable document: the reminders flag is
            // stored as text by some other build, and the rest timer beside it must survive
            // it — with its own default, which is not the same as the other three's.
            store.edit { it[stringPreferencesKey("notifications_reminders")] = "yes" }

            val notifications = repository.observe().first().notifications

            assertEquals(setOf(NotificationKind.REST_TIMER), notifications.enabled)
        }

    @Test
    fun `having asked for the permission is remembered, and cannot be un-asked`() =
        withRawStore { repository, store ->
            repository.setNotificationPermissionRequested()
            assertTrue(repository.observe().first().notifications.permissionRequested)

            // There is no setter back to false — the event cannot un-happen — so the only way
            // to check the flag is not merely being derived is to write it away by hand.
            store.edit { it[booleanPreferencesKey("notification_permission_requested")] = false }

            assertFalse(repository.observe().first().notifications.permissionRequested)
        }

    @Test
    fun `the notification settings survive process death`() {
        withStore { repository ->
            repository.setNotificationEnabled(NotificationKind.REMINDERS, true)
            repository.setNotificationEnabled(NotificationKind.REST_TIMER, false)
            repository.setNotificationPermissionRequested()
        }

        withStore { repository ->
            val notifications = repository.observe().first().notifications
            assertEquals(setOf(NotificationKind.REMINDERS), notifications.enabled)
            assertTrue(notifications.permissionRequested)
        }
    }

    /** [withRawStore] for the tests that have no reason to reach past the repository. */
    private fun withStore(block: suspend CoroutineScope.(UserPreferencesRepositoryImpl) -> Unit) =
        withRawStore { repository, _ -> block(repository) }

    /**
     * Runs [block] against a repository over [storeFile] and the store behind it, then
     * shuts the store down — see the note on this class for why the shutdown is the point.
     *
     * The store is handed over as well so a test can write raw keys behind the
     * repository's back, which is the only way to stage a file some other build left.
     */
    private fun withRawStore(
        block: suspend CoroutineScope.(UserPreferencesRepositoryImpl, DataStore<Preferences>) -> Unit,
    ) = runBlocking {
        // The real IO dispatcher, not a TestDispatcher: what is under test is a file, and
        // the concurrency tests need writers that genuinely overlap.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
        try {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                block(UserPreferencesRepositoryImpl(store), store)
            }
        } finally {
            // Both halves matter: cancel stops the store, and joining is what releases the
            // file, so the next store in a process-death test may open it.
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }

    /**
     * Waits until [seen] holds [count] emissions.
     *
     * A yield loop rather than `runCurrent`: the store reads and writes on the real IO
     * pool, so there is no virtual clock to drain, and a fixed sleep would be both slower
     * and flakier than waiting for the thing itself.
     */
    private suspend fun awaitEmissions(
        seen: List<*>,
        count: Int,
    ) {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (seen.size < count) yield()
        }
    }

    /** Gives an emission that was going to arrive enough turns to have arrived. */
    private suspend fun quiesce() {
        repeat(YIELDS_PER_QUIESCE) { yield() }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 10_000L
        const val YIELDS_PER_QUIESCE = 200

        /**
         * `UserPreferencesRepositoryImpl.RETRY_DELAY`, which is private to it.
         *
         * Duplicated rather than exposed: the retry cadence is an implementation detail
         * that no caller should be able to read, and the only thing these tests need from
         * it is a virtual clock long enough to cover several rounds of it. If the two ever
         * drift, the failure is a test that advances too little and reports a collector
         * that has not retried yet — visible, not silent.
         */
        val RETRY_DELAY: Duration = 1.seconds

        /** Enough rounds that "it retried" cannot be one retry that happened to be in flight. */
        const val RETRY_ROUNDS = 5

        /**
         * A rack that is nobody's default: three sizes, uneven counts, in kilos.
         *
         * Deliberately not [PlateInventory.METRIC], so a test that reads it back is asserting
         * that the *stored* rack came back rather than that some default did.
         */
        val HOME_RACK = PlateInventory(
            listOf(
                PlateStock(weight = 25_000_000, pairs = 2),
                PlateStock(weight = 20_000_000, pairs = 4),
                PlateStock(weight = 10_000_000, pairs = 1),
            ),
        )
    }
}

/** A store whose file cannot be read — the case `observe`'s `retryWhen` exists for. */
private object UnreadableDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw IOException("the file is not readable") }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        throw IOException("the file is not readable")
}

/**
 * A store that fails [failures] reads and then works — a full disk that gets emptied.
 *
 * [data] is re-collected from the top on every retry, the way `DataStore.data` is, and it
 * never completes on its own once it has emitted: a settings flow that ends is the thing
 * under test, so the fake must not end on its own and hand the test a false pass.
 */
private class FlakyDataStore(private var failures: Int) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow {
        if (failures > 0) {
            failures--
            throw IOException("the file is not readable yet")
        }
        emit(preferencesOf(stringPreferencesKey("theme") to ThemePreference.DARK.name))
        awaitCancellation()
    }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        throw UnsupportedOperationException("reads only")
}

/** A store failing for a reason that is not IO, which must reach the collector. */
private object BrokenDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { error("not an IO problem") }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        error("not an IO problem")
}
