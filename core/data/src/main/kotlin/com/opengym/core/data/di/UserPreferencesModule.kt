package com.opengym.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.opengym.core.data.repository.UserPreferencesRepositoryImpl
import com.opengym.core.domain.repository.UserPreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * The user settings store and the repository over it.
 *
 * `@Singleton` on the [DataStore] is load-bearing rather than an optimisation, the way it
 * is for `SessionStateRepositoryModule` and unlike the Room-backed bindings: DataStore
 * holds the file's write lock and its in-memory copy *per instance*, and it refuses
 * outright to have two live instances over the same file. An unscoped provider would
 * throw the first time two injection sites resolved it.
 *
 * The repository binding is scoped to match. It is a thin wrapper — the store behind it
 * is the shared thing — so a second instance would behave correctly; scoping it just
 * stops the flow being rebuilt per injection site.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UserPreferencesModule {

    @Binds
    @Singleton
    abstract fun bindUserPreferencesRepository(impl: UserPreferencesRepositoryImpl): UserPreferencesRepository

    companion object {

        /**
         * The file's name, without the `.preferences_pb` suffix
         * [preferencesDataStoreFile] adds. Renaming it abandons every existing install's
         * settings, silently — it is as much a persisted format as the keys inside.
         */
        private const val STORE_NAME = "user-preferences"

        /**
         * The store, built through the factory rather than the `preferencesDataStore`
         * property delegate: the delegate ties one store to one file *per Context*, which
         * is exactly the scoping `@Singleton` already provides, and it leaves no seam for
         * a test to supply its own file.
         *
         * [ReplaceFileCorruptionHandler] is the last resort, for a file that cannot be
         * read as preferences at all — not for a single bad value, which
         * `UserPreferencesRepositoryImpl` handles per key. Starting over with defaults is
         * the only alternative to throwing on the way to the first frame, and these are
         * settings: the cost is that the user re-picks them, not that anything is lost.
         *
         * The scope is deliberately never cancelled. It is the store's own lifetime, and
         * the store lives as long as the process does.
         */
        @Provides
        @Singleton
        fun provideUserPreferencesDataStore(
            @ApplicationContext context: Context,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(ioDispatcher + SupervisorJob()),
            produceFile = { context.preferencesDataStoreFile(STORE_NAME) },
        )
    }
}
