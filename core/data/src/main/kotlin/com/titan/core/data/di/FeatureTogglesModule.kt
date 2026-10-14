package com.titan.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.titan.core.data.feature.DataStoreFeatureToggles
import com.titan.core.domain.feature.FeatureToggles
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

/** Marks the [DataStore] holding the M8 kill-switches — see [UserPreferencesStore] for why it is qualified. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FeatureTogglesStore

@Module
@InstallIn(SingletonComponent::class)
abstract class FeatureTogglesModule {

    @Binds
    @Singleton
    abstract fun bindFeatureToggles(impl: DataStoreFeatureToggles): FeatureToggles

    companion object {

        /** Renaming this abandons every install's switches, which reads as everything off. */
        private const val STORE_NAME = "feature-toggles"

        @Provides
        @Singleton
        @FeatureTogglesStore
        fun provideFeatureTogglesDataStore(
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
