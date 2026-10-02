package com.titan.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import com.titan.core.data.di.AutoBackupStore
import com.titan.core.domain.backup.AutoBackupSchedule
import com.titan.core.domain.repository.AutoBackupRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * The auto-backup schedule, in a Preferences DataStore of its own.
 *
 * ### Its own file, not three more keys in `user-preferences`
 *
 * The settings store is a *user's* answers, and it is the thing an export carries and a restore
 * replaces. None of that is true of what is here: two of the three fields are a record of what
 * a background job did on *this* device, and the third would be a lie if it travelled — an
 * import writes settings, it does not enqueue work, so a restored `enabled = true` would draw a
 * toggle that is on with no schedule behind it. Sharing the file would also mean "delete all
 * data", which clears the settings store wholesale, silently changing a backup schedule the
 * user did not ask it to touch. A separate file costs one provider and keeps the two lifetimes
 * apart. See [AutoBackupSchedule] and docs/PERSISTENCE.md.
 *
 * Everything else follows `UserPreferencesRepositoryImpl`, deliberately: the same read contract
 * (fall back rather than throw, [retryWhen] rather than `catch` so a transient IO error does
 * not end the collection for the process's life), the same asymmetry on writes (they throw),
 * and the same per-key degradation — a timestamp that is not a plausible instant reads as "no
 * run" rather than as a date in 1970. The one addition is [read], which is that read contract
 * inverted for the one caller that needs it — see its note below.
 */
@Singleton
class AutoBackupRepositoryImpl @Inject constructor(
    @param:AutoBackupStore private val dataStore: DataStore<Preferences>,
) : AutoBackupRepository {

    override fun observe(): Flow<AutoBackupSchedule> =
        dataStore.data
            .retryWhen { cause, attempt ->
                if (cause !is IOException) return@retryWhen false
                // Defaults once, so the backup screen's first frame is drawn with the toggle
                // off rather than blank; then retry quietly behind it.
                if (attempt == 0L) emit(emptyPreferences())
                delay(RETRY_DELAY)
                true
            }
            .map(::toSchedule)
            .distinctUntilChanged()
            .conflate()

    /**
     * One cold read, with neither [retryWhen] nor a fallback under it.
     *
     * `dataStore.data` throws [IOException] when the file cannot be read — including the
     * `CorruptionException` a damaged one raises — and that is left to reach the caller. It is
     * the whole difference between this and [observe], and the reason the worker can tell an
     * unreadable store apart from a toggle the user turned off.
     */
    override suspend fun read(): AutoBackupSchedule = toSchedule(dataStore.data.first())

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[AutoBackupKeys.ENABLED] = enabled }
    }

    /**
     * Stamps the success and removes the failure, in one edit.
     *
     * `remove` rather than writing a zero, because zero is a value the reader would have to
     * special-case and an absent key already means "no failure" — see [toSchedule].
     */
    override suspend fun recordSuccess(at: Long) {
        dataStore.edit { preferences ->
            preferences[AutoBackupKeys.LAST_SUCCESS_AT] = at
            preferences.remove(AutoBackupKeys.LAST_FAILURE_AT)
        }
    }

    override suspend fun recordFailure(at: Long) {
        dataStore.edit { it[AutoBackupKeys.LAST_FAILURE_AT] = at }
    }

    /**
     * The schedule these preferences describe, with anything unusable read as absent.
     *
     * A non-positive timestamp is not an instant this app could have written — the clock has
     * been past 1970 for the whole of its existence — so it reads as "never", which is the
     * honest answer and the one the row already knows how to draw.
     */
    private fun toSchedule(preferences: Preferences): AutoBackupSchedule =
        AutoBackupSchedule(
            enabled = preferences[AutoBackupKeys.ENABLED] == true,
            lastSuccessAt = preferences[AutoBackupKeys.LAST_SUCCESS_AT]?.takeIf { it > 0 },
            lastFailureAt = preferences[AutoBackupKeys.LAST_FAILURE_AT]?.takeIf { it > 0 },
        )

    private companion object {

        /** Long enough that a permanently unreadable store does not spin the IO pool. */
        val RETRY_DELAY = 1.seconds
    }
}

/**
 * The store's keys.
 *
 * String literals in one place, as in `UserPreferencesKeys`: a key name is a persisted format,
 * and deriving one from a property name would let a refactor reset every install's schedule
 * without a line of the diff saying so.
 */
private object AutoBackupKeys {

    val ENABLED = booleanPreferencesKey("auto_backup_enabled")
    val LAST_SUCCESS_AT = longPreferencesKey("auto_backup_last_success_at")
    val LAST_FAILURE_AT = longPreferencesKey("auto_backup_last_failure_at")
}
