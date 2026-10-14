package com.titan.core.data.feature

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.titan.core.domain.feature.M8Feature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreFeatureTogglesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file: File by lazy { File(temporaryFolder.root, "toggles.preferences_pb") }
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { file } }
    private val toggles by lazy { DataStoreFeatureToggles(store) }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `every feature is off on a fresh install`() =
        runBlocking {
            M8Feature.entries.forEach { assertFalse(it.name, toggles.isEnabled(it)) }
        }

    @Test
    fun `a switch is stored per feature and observed`() =
        runBlocking {
            toggles.setEnabled(M8Feature.HEALTH_CONNECT, true)

            assertTrue(toggles.observe(M8Feature.HEALTH_CONNECT).first())
            assertFalse(toggles.isEnabled(M8Feature.GYM_DIRECTORY))

            toggles.setEnabled(M8Feature.HEALTH_CONNECT, false)
            assertFalse(toggles.isEnabled(M8Feature.HEALTH_CONNECT))
        }

    @Test
    fun `the stored keys are stable`() =
        runBlocking {
            toggles.setEnabled(M8Feature.MARKETPLACE_INGESTION, true)

            val stored = store.data.first()[booleanPreferencesKey("m8_marketplace_ingestion")]

            assertEquals(true, stored)
        }

    @Test
    fun `a store written elsewhere reads back`() =
        runBlocking {
            store.edit { it[booleanPreferencesKey("m8_gym_reviews")] = true }

            assertTrue(toggles.isEnabled(M8Feature.GYM_REVIEWS))
        }
}
