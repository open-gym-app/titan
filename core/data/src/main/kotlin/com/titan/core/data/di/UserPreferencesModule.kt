package com.titan.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.titan.core.data.repository.UserPreferencesRepositoryImpl
import com.titan.core.domain.notification.NotificationPermission
import com.titan.core.domain.repository.UserPreferencesRepository
import com.titan.core.domain.usecase.RequestNotificationPermissionUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks the [DataStore] holding the user's settings.
 *
 * `DataStore<Preferences>` is a type the whole app shares, and every preferences store
 * anyone ever adds — an onboarding scratch pad, a debug-flags file — has exactly it. An
 * unqualified binding therefore makes the *second* such store a duplicate-binding error,
 * and, worse, makes any later `@Inject constructor(store: DataStore<Preferences>)`
 * elsewhere compile cleanly while writing its keys into the settings file. The qualifier
 * costs a line and makes the graph name a file rather than a type.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UserPreferencesStore

/**
 * The directory DataStore keeps its files in, relative to the root it is given.
 *
 * Not a name this app chose — [preferencesDataStoreFile] hardcodes it under `filesDir` — but a
 * name this app has to know twice over. `AutoBackupModule` builds its path by hand to reach
 * `no_backup/` and spells the directory itself, and `:app`'s backup rules exclude the whole
 * directory rather than the one file inside it, because a store writes a `.tmp` sibling on
 * every commit and a second store put here later would otherwise travel to Google's servers.
 * `UserPreferencesStoreLocationTest` is what keeps this constant and DataStore's own layout
 * the same fact.
 */
const val DATASTORE_DIRECTORY = "datastore"

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
         * The decision to raise the notification permission prompt, shared by the two screens
         * that turn a notification on — see [RequestNotificationPermissionUseCase] for why it
         * is one decision rather than two copies of it.
         *
         * `@Provides` rather than an `@Inject constructor` for the reason every other use case
         * here is provided: `:core:domain` is a plain JVM library with no Hilt on its
         * classpath. Unscoped, per ARCHITECTURE.md's table — it is a stateless composition of
         * two singletons.
         */
        @Provides
        fun provideRequestNotificationPermissionUseCase(
            userPreferencesRepository: UserPreferencesRepository,
            permission: NotificationPermission,
        ): RequestNotificationPermissionUseCase =
            RequestNotificationPermissionUseCase(userPreferencesRepository, permission)

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
        @UserPreferencesStore
        fun provideUserPreferencesDataStore(
            @ApplicationContext context: Context,
            @IoDispatcher ioDispatcher: CoroutineDispatcher,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                scope = CoroutineScope(ioDispatcher + SupervisorJob()),
                produceFile = { context.preferencesDataStoreFile(STORE_NAME) },
            )
    }
}
