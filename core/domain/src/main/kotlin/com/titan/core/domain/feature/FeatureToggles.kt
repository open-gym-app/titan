package com.titan.core.domain.feature

import kotlinx.coroutines.flow.Flow

/** The local kill-switches. Checked at each feature's entry point, never deeper. */
interface FeatureToggles {

    fun observe(feature: M8Feature): Flow<Boolean>

    suspend fun isEnabled(feature: M8Feature): Boolean

    suspend fun setEnabled(
        feature: M8Feature,
        enabled: Boolean,
    )
}
