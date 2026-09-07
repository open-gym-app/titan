package com.opengym.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.opengym.core.data.backup.WorkManagerAutoBackupScheduler
import com.opengym.core.data.repository.AutoBackupRepositoryImpl
import com.opengym.core.domain.backup.AutoBackupScheduler
import com.opengym.core.domain.repository.AutoBackupRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the [DataStore] holding the auto-backup schedule.
 *
 * The second store in the app, and the reason [UserPreferencesStore] was qualified in the first
 * place: `DataStore<Preferences>` is one type over any number of files, so without a qualifier
 * this would be a duplicate binding — or, worse, would compile and write its keys into the
 * settings file.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AutoBackupStore

/**
 * The auto-backup store, the repository over it, and the WorkManager-backed scheduler.
 *
 * `@Singleton` on the [DataStore] is load-bearing exactly as it is for the settings store:
 * DataStore holds a file's write lock and its in-memory copy per instance and refuses two live
 * instances over one file, so an unscoped provider would throw the first time two injection
 * sites resolved it. The scheduler is scoped because it is stateless glue whose one field is
 * the process-wide `WorkManager`, and the repository to match its store.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AutoBackupModule {

    @Binds
    @Singleton
    abstract fun bindAutoBackupRepository(impl: AutoBackupRepositoryImpl): AutoBackupRepository

    @Binds
    @Singleton
    abstract fun bindAutoBackupScheduler(impl: WorkManagerAutoBackupScheduler): AutoBackupScheduler

    companion object {

        /**
         * The file, under `no_backup/` rather than under `files/`.
         *
         * As persisted a path as the keys inside it: moving it turns every install's schedule
         * off and forgets when it last ran.
         *
         * **Excluded from Android's own backup, deliberately**, which is the same reasoning
         * that keeps the schedule out of the app's export envelope carried one step further.
         * `android:allowBackup` is on, so anything under `files/` travels to a new device in a
         * cloud restore or a device-to-device transfer — while WorkManager keeps its own
         * database in `no_backup/` and therefore does not. A store that travelled would land on
         * the new phone with `auto_backup_enabled = true` and no work enqueued behind it: a
         * switch drawn on, saying "Last backup 26 Apr", over a schedule that will never run
         * again. Sharing WorkManager's directory is what keeps the two facts one fact.
         */
        private fun storeFile(context: Context): File =
            File(File(context.noBackupFilesDir, DATASTORE_DIRECTORY), "$STORE_NAME.preferences_pb")

        private const val STORE_NAME = "auto-backup"

        /**
         * Built through the factory rather than the `preferencesDataStore` delegate, for
         * `UserPreferencesModule`'s reasons: the delegate's one-store-per-Context scoping is
         * what `@Singleton` already gives, and it leaves no seam for a test to supply a file.
         *
         * A file that cannot be read as preferences at all starts over empty, which reads as a
         * toggle that is off and a schedule that has never run. That is the safe direction for
         * a corruption handler on a feature that writes files: the user is shown the truth
         * about what this device knows, and turning it back on re-enqueues the work.
         */
        @Provides
        @Singleton
        @AutoBackupStore
        fun provideAutoBackupDataStore(
            @ApplicationContext context: Context,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = CoroutineScope(ioDispatcher + SupervisorJob()),
                produceFile = { storeFile(context) },
            )
    }
}
