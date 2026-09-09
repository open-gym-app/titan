package com.opengym.core.data.repository

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.opengym.core.data.db.DatabaseSeed
import com.opengym.core.data.db.OpenGymDatabase
import com.opengym.core.data.di.DATABASE_NAME
import com.opengym.core.data.di.IoDispatcher
import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.notification.ReminderScheduler
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.LocalDataRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.repository.TemplateDraftRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The whole of the device's local state, read as counts and erased as one operation.
 *
 * It is the only class that holds all four stores at once — the database, the settings, the
 * in-progress draft file and the in-memory session state — and that is the point: the
 * knowledge of *what "all of it" consists of* lives in exactly one place, so a store added
 * later is a line here rather than a thing the delete-all dialog quietly stops covering.
 *
 * The workout reminder's **alarms** are the one thing here that is not a store, and they are
 * here for the same reason: they are state this device is holding on the user's behalf, they
 * outlive the process, and the settings wipe is what makes them wrong.
 *
 * ### Order matters, and it is the order of consequences
 *
 * [deleteAll] works from the most visible thing to the least:
 *
 * 1. **The session state goes to [SessionState.Idle] first.** It is in memory and it is what
 *    screens are collecting; leaving it pointing at a session id while that row is deleted
 *    means a live screen reading a session that no longer exists for as long as the wipe
 *    takes.
 * 2. **The draft file next**, so a process death in the middle of this cannot leave a
 *    resumable workout referring to rows that are about to go.
 * 3. **Then the rows**, which is the part that actually takes time.
 * 4. **Settings last**, and the alarms behind them with the same step. They are the cheapest
 *    thing to lose and the least alarming to keep: a wipe that dies before this leaves a user
 *    with the right units and no history, which is strictly better than the reverse.
 *
 * ### The catalogue is put back, not spared
 *
 * `clearAllTables` is all-or-nothing over every registered entity, so the seeded exercise
 * library goes with the user's rows and is replayed immediately afterwards. Replaying is
 * sound because the seed is `INSERT OR IGNORE` over unique indexes — the same property that
 * makes it safe to run on every open — and it is *necessary* because an app whose exercise
 * library is empty is broken rather than clean. Exercises the user typed in are not restored,
 * because those are theirs and this is a wipe.
 *
 * ### Failure is loud
 *
 * Nothing in [deleteAll] catches. A wipe that half-worked and reported success is the one
 * outcome the contract forbids, so a failed table clear or an unwritable settings file
 * propagates and the caller tells the user. The counts in [observeFootprint] are the opposite,
 * and for the usual reason: they exist to draw a dialog, and a dialog that cannot draw is
 * worse than one with zeroes in it.
 */
@Singleton
class LocalDataRepositoryImpl @Inject constructor(
    private val database: OpenGymDatabase,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    private val templateDraftRepository: TemplateDraftRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val reminderScheduler: ReminderScheduler,
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LocalDataRepository {

    /*
     * Reached through the database rather than injected beside it. The two would always be
     * the same database, and a constructor that takes both invites the day they are not.
     */
    private val localDataDao get() = database.localDataDao()

    /**
     * The six counts, as one value, and never at the cost of the flow itself.
     *
     * [retryWhen] rather than [kotlinx.coroutines.flow.catch], for the reason
     * `UserPreferencesRepositoryImpl.observe` spells out at length: `catch` emits *and then
     * completes*, so one transient `SQLITE_BUSY` from any of the six queries would pin the
     * footprint at zeroes for as long as the ViewModel lives — and zeroes are the value the
     * dialog renders as "there is nothing logged on this phone yet" to a user with two hundred
     * sessions. Emitting the zeroes and staying subscribed keeps the interface's "re-emitted as
     * it changes" true past the first hiccup.
     */
    override fun observeFootprint(): Flow<DataFootprint> =
        combine(
            combine(
                localDataDao.countSessions(),
                localDataDao.countSets(),
                localDataDao.countTemplates(),
                localDataDao.countPersonalRecords(),
                localDataDao.countCustomExercises(),
                localDataDao.countBodyMeasurements(),
            ) { it },
            localDataDao.firstSessionAt(),
        ) { counts, firstSessionAt ->
            DataFootprint(
                sessions = counts[SESSIONS],
                sets = counts[SETS],
                templates = counts[TEMPLATES],
                personalRecords = counts[PERSONAL_RECORDS],
                customExercises = counts[CUSTOM_EXERCISES],
                bodyMeasurements = counts[BODY_MEASUREMENTS],
                firstSessionAt = firstSessionAt,
                // Read here rather than observed, because a file's length is not something
                // SQLite can notify on: it changes on a checkpoint and a vacuum as much as on
                // a write. Recomputing it whenever a count changes is the closest honest
                // approximation, and it is one `stat` against six queries that have just run.
                databaseBytes = databaseBytes(),
            )
        }
            .retryWhen { cause, attempt ->
                // Only storage failures. A wrong index in the mapping above is a bug, and
                // retrying it forever would hide it behind a dialog full of zeroes.
                if (cause !is SQLiteException && cause !is IOException) return@retryWhen false
                // An unreadable database is not a reason to withhold the confirmation dialog, so
                // a collector that has not been handed anything yet still gets its first frame.
                // The delete action stays available either way, and the cost of a wrong zero is
                // one imprecise sentence rather than a user locked out of erasing their own data.
                if (attempt == 0L) emit(DataFootprint())
                delay(RETRY_DELAY)
                true
            }
            .distinctUntilChanged()
            .conflate()
            // The transform stats three files, and a collector is a ViewModel on Main. One
            // `flowOn` keeps that syscall — and Room's own delivery — off the frame the
            // settings screen is drawing.
            .flowOn(ioDispatcher)

    /**
     * [NonCancellable], which is the one place in the app that earns it.
     *
     * This is called from `viewModelScope`, so the plain version is cancelled when the settings
     * screen goes away — and every suspension point after [OpenGymDatabase.clearAllTables] is
     * a step that has to happen *because* the tables were cleared: the catalogue replay and
     * the settings wipe. Being cancelled between them leaves a device with no exercise
     * library, or with its history gone and every setting kept, and in both cases with nothing
     * said to anyone. Every other operation in this class is worth abandoning when its screen
     * leaves; the irreversible one is not.
     */
    override suspend fun deleteAll() {
        withContext(ioDispatcher + NonCancellable) {
            sessionStateRepository.update(SessionState.Idle)
            inProgressSessionRepository.clear()
            // And the half-built template, for the same reason: it names a template id that is
            // about to stop existing, and a draft left behind would be restored over the next
            // template the user opens.
            templateDraftRepository.clear()
            database.clearAllTables()
            // Through Room's transaction rather than straight at the writable database:
            // `clearAllTables` notifies the invalidation tracker that the catalogue emptied,
            // and a raw `beginTransaction` on the support database would not notify it that
            // the seed came back — leaving any live query over `exercise` (an exercise-library
            // screen further down the back stack) showing an empty catalogue until some
            // unrelated write refreshed it.
            database.withTransaction {
                DatabaseSeed.insertInto(database.openHelper.writableDatabase, context.assets)
            }
            userPreferencesRepository.clear()
            // And the alarms the settings had pointed at. `clear()` puts the reminder
            // preferences back to their defaults — which include the switch being *off* — so
            // without this a wiped phone goes on buzzing at 18:00 on Mondays for a reminder
            // its own settings screen reports as turned off. `sync` re-reads the store it has
            // just been emptied and takes down everything it does not want.
            reminderScheduler.sync()
        }
    }

    /**
     * How much space the database occupies, in bytes, or **0** if it cannot be measured.
     *
     * The three files SQLite keeps in WAL mode, not just the main one: the write-ahead log is
     * routinely tens of megabytes between checkpoints and a figure that ignored it would read
     * as a fraction of what `du` says. `Context.getDatabasePath` resolves a bare name to the
     * app's own database directory, so the WAL and the shared-memory file are its siblings.
     *
     * The name is [DATABASE_NAME], the one `DatabaseModule` opens Room with, rather than a
     * second copy of the string: reaching the open file's path off the instance
     * (`openHelper.databaseName`) is null for the in-memory database every test builds, so a
     * shared constant is what keeps a rename from silently zeroing this.
     *
     * Zero rather than a failure. This exists to put an order of magnitude on a backup
     * screen, and a device where the file cannot be stat'd is not a device that should be
     * unable to draw one — the same call [observeFootprint] makes about the counts.
     */
    private fun databaseBytes(): Long {
        val main = context.getDatabasePath(DATABASE_NAME)
        return runCatching {
            listOf(main, File(main.path + WAL_SUFFIX), File(main.path + SHM_SUFFIX))
                .filter { it.exists() }
                .sumOf { it.length() }
        }.getOrDefault(0)
    }

    /*
     * `combine`'s six-flow form hands the values over as an array, so the positions are named
     * once here rather than as bare indices in the middle of the constructor call. Getting one
     * wrong swaps two plausible numbers in a dialog nobody reads twice.
     */
    private companion object {

        /** SQLite's write-ahead log and shared-memory siblings, both next to the main file. */
        const val WAL_SUFFIX = "-wal"
        const val SHM_SUFFIX = "-shm"

        /**
         * How long [observeFootprint] waits before re-reading a database that failed. The same
         * second `UserPreferencesRepositoryImpl` waits, and for the same reason: a store that
         * is never going to be readable costs a wakeup a second rather than a busy loop.
         */
        val RETRY_DELAY: Duration = 1.seconds

        const val SESSIONS = 0
        const val SETS = 1
        const val TEMPLATES = 2
        const val PERSONAL_RECORDS = 3
        const val CUSTOM_EXERCISES = 4
        const val BODY_MEASUREMENTS = 5
    }
}
