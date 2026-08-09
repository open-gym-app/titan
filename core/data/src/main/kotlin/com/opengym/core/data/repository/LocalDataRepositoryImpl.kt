package com.opengym.core.data.repository

import android.content.Context
import com.opengym.core.data.db.DatabaseSeed
import com.opengym.core.data.db.OpenGymDatabase
import com.opengym.core.data.di.IoDispatcher
import com.opengym.core.domain.model.DataFootprint
import com.opengym.core.domain.model.SessionState
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.LocalDataRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The whole of the device's local state, read as counts and erased as one operation.
 *
 * It is the only class that holds all four stores at once — the database, the settings, the
 * in-progress draft file and the in-memory session state — and that is the point: the
 * knowledge of *what "all of it" consists of* lives in exactly one place, so a store added
 * later is a line here rather than a thing the delete-all dialog quietly stops covering.
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
 * 4. **Settings last.** They are the cheapest thing to lose and the least alarming to keep:
 *    a wipe that dies before this leaves a user with the right units and no history, which
 *    is strictly better than the reverse.
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
 * Nothing here catches. A wipe that half-worked and reported success is the one outcome the
 * contract forbids, so a failed table clear or an unwritable settings file propagates and the
 * caller tells the user. The counts in [observeFootprint] are the opposite, and for the usual
 * reason: they exist to draw a dialog, and a dialog that cannot draw is worse than one with
 * zeroes in it.
 */
@Singleton
class LocalDataRepositoryImpl @Inject constructor(
    private val database: OpenGymDatabase,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val inProgressSessionRepository: InProgressSessionRepository,
    private val sessionStateRepository: SessionStateRepository,
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LocalDataRepository {

    /*
     * Reached through the database rather than injected beside it. The two would always be
     * the same database, and a constructor that takes both invites the day they are not.
     */
    private val localDataDao get() = database.localDataDao()

    override fun observeFootprint(): Flow<DataFootprint> = combine(
        localDataDao.countSessions(),
        localDataDao.countSets(),
        localDataDao.countTemplates(),
        localDataDao.countPersonalRecords(),
        localDataDao.countCustomExercises(),
        localDataDao.countBodyWeightLogs(),
    ) { counts ->
        DataFootprint(
            sessions = counts[SESSIONS],
            sets = counts[SETS],
            templates = counts[TEMPLATES],
            personalRecords = counts[PERSONAL_RECORDS],
            customExercises = counts[CUSTOM_EXERCISES],
            bodyWeightLogs = counts[BODY_WEIGHT_LOGS],
        )
    }
        // An unreadable database is not a reason to withhold the confirmation dialog. The
        // delete action stays available either way, so the cost of a wrong zero is one
        // imprecise sentence rather than a user locked out of erasing their own data.
        .catch { emit(DataFootprint()) }
        .distinctUntilChanged()
        .conflate()

    override suspend fun deleteAll() {
        withContext(ioDispatcher) {
            sessionStateRepository.update(SessionState.Idle)
            inProgressSessionRepository.clear()
            database.clearAllTables()
            DatabaseSeed.insertInto(database.openHelper.writableDatabase, context.assets)
            userPreferencesRepository.clear()
        }
    }

    /*
     * `combine`'s six-flow form hands the values over as an array, so the positions are named
     * once here rather than as bare indices in the middle of the constructor call. Getting one
     * wrong swaps two plausible numbers in a dialog nobody reads twice.
     */
    private companion object {
        const val SESSIONS = 0
        const val SETS = 1
        const val TEMPLATES = 2
        const val PERSONAL_RECORDS = 3
        const val CUSTOM_EXERCISES = 4
        const val BODY_WEIGHT_LOGS = 5
    }
}
