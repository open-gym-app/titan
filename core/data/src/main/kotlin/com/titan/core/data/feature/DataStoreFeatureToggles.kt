package com.titan.core.data.feature

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.titan.core.data.di.FeatureTogglesStore
import com.titan.core.domain.feature.FeatureToggles
import com.titan.core.domain.feature.M8Feature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The kill-switches, one boolean per feature in a file of their own.
 *
 * Absent means off: a fresh install, a cleared store and an unreadable value all read as
 * "not opted in", which is the state that changes nothing for the user.
 */
class DataStoreFeatureToggles @Inject constructor(@FeatureTogglesStore private val store: DataStore<Preferences>) :
    FeatureToggles {

    override fun observe(feature: M8Feature): Flow<Boolean> = store.data.map { it[keyOf(feature)] ?: false }

    override suspend fun isEnabled(feature: M8Feature): Boolean = observe(feature).first()

    override suspend fun setEnabled(
        feature: M8Feature,
        enabled: Boolean,
    ) {
        store.edit { it[keyOf(feature)] = enabled }
    }

    private fun keyOf(feature: M8Feature) = booleanPreferencesKey("m8_${feature.name.lowercase()}")
}
