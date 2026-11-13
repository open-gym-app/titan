package com.titan.core.data.repository

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.titan.core.data.backup.AutoBackupFiles
import com.titan.core.data.db.DatabaseSeed
import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.di.DATABASE_NAME
import com.titan.core.data.di.IoDispatcher
import com.titan.core.domain.backup.BackupLock
import com.titan.core.domain.model.DataFootprint
import com.titan.core.domain.model.SessionState
import com.titan.core.domain.notification.ReminderScheduler
import com.titan.core.domain.repository.AutoBackupRepository
import com.titan.core.domain.repository.InProgressSessionRepository
import com.titan.core.domain.repository.LocalDataRepository
import com.titan.core.domain.repository.SessionStateRepository
import com.titan.core.domain.repository.TemplateDraftRepository
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.session.IdleSessionMonitor
import com.titan.core.domain.timer.BackgroundRestTimer
import com.titan.core.domain.usecase.SessionLifecycleLock
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
 * It is the only class that holds every store at once — the database, the settings, the
 * in-progress draft file, the in-memory session state and the backup files — and that is the
 * point: the knowledge of *what "all of it" consists of* lives in exactly one place, so a store
 * added later is a line here rather than a thing the delete-all dialog quietly stops covering.
 *
 * ### What it holds that is not a store
 *
 * Three things the operating system keeps on the app's behalf, which outlive the process and
 * which the wipe makes wrong:
 *
 * - **The workout's rest**: its note in the shade and its exact alarm, both naming the session
 *   being rested in. Left standing, the alarm fires after the wipe and announces "Rest over" for
 *   a workout that no longer exists. [BackgroundRestTimer] takes both down.
 * - **The idle nudge**: its alarm and its note, which [IdleSessionMonitor] takes down.
 * - **The workout reminder's alarms**, which follow the settings: [ReminderScheduler] re-reads
 *   the store once it has been emptied.
 *
 * The weekly backup's *schedule* is not one of them: the toggle and its enqueued work are left
 * as they are. The toggle lives in its own store precisely so that this wipe does not change a
 * schedule the user did not ask it to touch (docs/PERSISTENCE.md), and cancelling the work under
 * a toggle that still read on would draw a switch with nothing behind it. Nor is cancelling
 * needed to keep a run out of the wipe — `BackupLock` does that: a run that was writing finishes
 * first and its file is then deleted with the rest, and one that starts afterwards backs up the
 * emptied device. What goes is the record of past runs, whose files this deletes.
 *
 * ### Order matters, and it is the order of consequences
 *
 * [deleteAll] works from the most visible thing to the least:
 *
 * 1. **The session state goes to [SessionState.Idle] first.** It is in memory and it is what
 *    screens are collecting; leaving it pointing at a session id while that row is deleted
 *    means a live screen reading a session that no longer exists for as long as the wipe
 *    takes. The rest and the idle nudge go with it, for the same reason: they are on the lock
 *    screen.
 * 2. **The draft file next**, so a process death in the middle of this cannot leave a
 *    resumable workout referring to rows that are about to go.
 * 3. **Then the rows**, which is the part that actually takes time, and the backup files
 *    after them, which are copies of the same rows.
 * 4. **Settings last**, and the reminder's alarms behind them with the same step. They are the
 *    cheapest thing to lose and the least alarming to keep: a wipe that dies before this leaves
 *    a user with the right units and no history, which is strictly better than the reverse.
 *
 * ### It waits for a backup, and holds back session transitions
 *
 * The whole wipe runs under [BackupLock], so it neither lands in the middle of an export —
 * manual or the weekly one, which would write a file that is half the history and half the
 * wipe — nor between a restore's rows and its settings. It also holds [SessionLifecycleLock]
 * from the session reset to the end of the wipe, so a session started from a notification in
 * that window cannot be inserted into tables that are about to be cleared, or survive them in
 * the session state.
 *
 * Both are taken before anything is touched, and the wait for them can be cancelled: a delete
 * whose screen goes away while a backup is still writing has deleted nothing.
 *
 * ### The catalogue is put back, not spared
 *
 * `clearAllTables` is all-or-nothing over every registered entity, so the seeded exercise
 * library goes with the user's rows and is replayed immediately afterwards, and the default
 * templates with it: a wipe leaves the device as a fresh install would, with every default
 * back in the template list whatever the user had done to them. Replaying is *necessary*
 * because an app whose exercise library is empty is broken rather than clean.
 * Exercises the user typed in are not restored, because those are theirs and this is a wipe.
 *
 * The ids start again from one, as on a fresh install, for the same reason — see [wipe].
 *
 * ### Failure is loud
 *
 * Nothing in [deleteAll] catches. A wipe that half-worked and reported success is the one
 * outcome the contract forbids, so a failed table clear, a backup file that will not delete or
 * an unwritable settings file propagates and the caller tells the user. A backup file that will
 * not delete is the one failure reported late: the settings and the alarms behind it are
 * independent of it, so they are reset first and the failure is thrown after them, leaving the
 * device as close to what the user asked for as it can be. The counts in
 * [observeFootprint] are the opposite, and for the usual reason: they exist to draw a dialog,
 * and a dialog that cannot draw is worse than one with zeroes in it.
 */
@Singleton
class LocalDataRepositoryImpl @Inject constructor(
    private val database: TitanDatabase,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    private val templateDraftRepository: TemplateDraftRepository,
    private val sessionStateRepository: SessionStateRepository,
    private val backgroundRestTimer: BackgroundRestTimer,
    private val idleSessionMonitor: IdleSessionMonitor,
    private val reminderScheduler: ReminderScheduler,
    private val backupFiles: AutoBackupFiles,
    private val autoBackupRepository: AutoBackupRepository,
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LocalDataRepository {

    /*
     * Reached through the database rather than injected beside it. The two would always be
     * the same database, and a constructor that takes both invites the day they are not.
     */
    private val localDataDao get() = database.localDataDao()

    /*
     * The process-wide instances rather than constructor parameters, for the reason
     * `SessionUseCaseModule` gives for the use cases: the lock's identity is the domain's
     * invariant, and binding it in the graph would make it something a module could replace.
     */
    private val backupLock = BackupLock.SHARED
    private val sessionLock = SessionLifecycleLock.SHARED

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
     * screen goes away — and every suspension point after [TitanDatabase.clearAllTables] is
     * a step that has to happen *because* the tables were cleared: the catalogue replay and
     * the settings wipe. Being cancelled between them leaves a device with no exercise
     * library, or with its history gone and every setting kept, and in both cases with nothing
     * said to anyone. Every other operation in this class is worth abandoning when its screen
     * leaves; the irreversible one is not.
     *
     * The locks are taken outside it, so waiting for them is the one part that can still be
     * abandoned — see the class doc.
     */
    override suspend fun deleteAll() {
        backupLock.exclusively {
            sessionLock.withTransition {
                withContext(ioDispatcher + NonCancellable) { wipe() }
            }
        }
    }

    /**
     * The wipe itself, in the order the class doc gives.
     *
     * ### The id counters are reset with the rows
     *
     * `clearAllTables` empties every table but not `sqlite_sequence`, where `AUTOINCREMENT`
     * remembers the largest id each table has ever held and never assigns one below it. Kept,
     * it carries the old history's numbering into the new one, and one id near `Long.MAX_VALUE`
     * — a restore made before such ids were refused could write one — leaves nothing to assign:
     * every later insert into that table fails with `SQLITE_FULL`. Clearing it is what a fresh
     * install has.
     *
     * That gives the old numbers out again, and nothing that outlives the wipe acts on one. The
     * rows and the drafts are gone, the rest and the idle nudge come down above, and an intent
     * that was already on its way names its session together with the row's creation time,
     * which a new workout under the same number does not share. A restore already writes back
     * ids this device has used before, and those defences are built for it.
     */
    private suspend fun wipe() {
        sessionStateRepository.update(SessionState.Idle)
        // The workout's note and alarm, and the nudge's: both name the session about to go, and
        // the operating system keeps them whatever happens to the rows. Neither class needs the
        // session to still exist to take its own state down.
        backgroundRestTimer.set(null)
        idleSessionMonitor.disarm()
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
            val writable = database.openHelper.writableDatabase
            // Before the seed, which writes the catalogue's ids explicitly and so sets the
            // counters for its own tables back to where a fresh install has them.
            writable.execSQL(RESET_ID_COUNTERS)
            DatabaseSeed.insertInto(writable, context)
        }
        val stuckBackups = deleteBackups()
        // The dates the backup screen shows under the toggle describe the files just deleted.
        autoBackupRepository.forgetRuns()
        userPreferencesRepository.clear()
        // And the alarms the settings had pointed at. `clear()` puts the reminder
        // preferences back to their defaults — which include the switch being *off* — so
        // without this a wiped phone goes on buzzing at 18:00 on Mondays for a reminder
        // its own settings screen reports as turned off. `sync` re-reads the store it has
        // just been emptied and takes down everything it does not want.
        reminderScheduler.sync()
        if (stuckBackups.isNotEmpty()) {
            throw IOException("could not delete ${stuckBackups.joinToString { it.path }}")
        }
    }

    /**
     * Empties `backups/`: every manual export, every automatic backup, and the `.json.tmp`
     * scratch file either may have left. Each is the whole history in plain JSON, which is
     * exactly what the user has just asked to erase.
     *
     * The directories are [AutoBackupFiles.everyDirectory], from the one place in this module
     * that resolves them, so a move of the backups is followed here without a second path to
     * keep in step. Both roots rather than [AutoBackupFiles.directory] alone, because that one
     * follows whichever volume is mounted now, and a backup written while the external volume
     * was away sits in the internal fallback. Resolving first also moves in any an older install
     * left under the other root.
     *
     * Two passes, because an export renames its scratch file into place *after* it lets go of
     * [BackupLock]. One that finished just before the wipe took the lock can rename a scratch
     * file the first pass has listed and not yet reached; the second pass finds it under its
     * finished name. Nothing can start writing a third: every export needs the lock this holds.
     *
     * An export waiting for the lock may already have opened its empty scratch file; deleted or
     * not, it holds nothing yet, and once the lock is free it can only be written with the
     * emptied device.
     *
     * Answers with the files the last pass tried to delete and found still there, rather than
     * throwing at once: history left on a device the user asked to clear is a failure to
     * report, and [wipe] reports it, but only after the settings and the alarms that do not
     * depend on it. One that vanished between the listing and the delete is not stuck.
     */
    private fun deleteBackups(): List<File> {
        val directories = backupFiles.everyDirectory
        var stuck = emptyList<File>()
        repeat(BACKUP_DELETE_PASSES) {
            stuck = directories
                .flatMap { it.listFiles().orEmpty().asList() }
                .filter { !it.deleteRecursively() && it.exists() }
        }
        return stuck
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
         * Forgets the largest id every `AUTOINCREMENT` table has held. SQLite allows ordinary
         * `DELETE` on its own bookkeeping table for exactly this; see [wipe].
         */
        const val RESET_ID_COUNTERS = "DELETE FROM sqlite_sequence"

        /** See [deleteBackups] for why one pass is not enough. */
        const val BACKUP_DELETE_PASSES = 2

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
