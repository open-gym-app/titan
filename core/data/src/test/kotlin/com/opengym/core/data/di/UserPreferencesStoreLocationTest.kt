package com.opengym.core.data.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [DATASTORE_DIRECTORY] against the directory DataStore actually uses.
 *
 * The constant is a name this app did not choose and cannot change: [preferencesDataStoreFile]
 * hardcodes it, and `UserPreferencesModule` builds the settings store through that function.
 * Two other places nonetheless have to know it — `AutoBackupModule`, which spells the path by
 * hand to reach `no_backup/`, and `:app`'s backup rules, which exclude the whole directory
 * rather than the one file in it — and neither would fail if DataStore moved. This is the
 * assertion that would: the settings store travelling to a new device in a cloud restore is a
 * silent failure with the user's answers on Google's servers behind it.
 *
 * The store name is arbitrary here. The directory is the fact under test, and it is the same
 * for every store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserPreferencesStoreLocationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `DataStore keeps its files in the directory the backup rules exclude`() {
        val store = context.preferencesDataStoreFile("any-store")

        assertEquals(File(context.filesDir, DATASTORE_DIRECTORY), store.parentFile)
    }
}
