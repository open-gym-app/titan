package com.titan.core.data.di

import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import com.titan.core.data.worker.BackupWorker
import com.titan.core.data.worker.HistoryImportWorker
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * That `HiltWorkerFactory` — the factory `TitanApplication` hands WorkManager — can build every
 * worker in this module from the real graph.
 *
 * A worker is constructed by WorkManager, not by anything Dagger checks at compile time against
 * a caller, so a dependency nobody binds is first seen when the job runs: at three in the morning
 * for the weekly backup, and as an import that never starts for the import from another app.
 * Building each one here is the earliest that can fail.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class, manifest = Config.NONE)
class WorkerBindingsTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun inject() {
        hilt.inject()
    }

    @Test
    fun `the weekly backup worker is built from the graph`() {
        val worker = TestListenableWorkerBuilder<BackupWorker>(context).setWorkerFactory(workerFactory).build()

        assertEquals(BackupWorker::class.java, worker.javaClass)
    }

    @Test
    fun `the import worker is built from the graph`() {
        val worker = TestListenableWorkerBuilder<HistoryImportWorker>(context).setWorkerFactory(workerFactory).build()

        assertEquals(HistoryImportWorker::class.java, worker.javaClass)
    }
}
