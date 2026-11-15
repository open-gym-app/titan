package com.titan.core.data.di

import com.titan.core.domain.usecase.ApplyTemplateUseCase
import com.titan.core.domain.usecase.ExportUseCase
import com.titan.core.domain.usecase.GetTemplateSummariesUseCase
import com.titan.core.domain.usecase.ImportUseCase
import com.titan.core.domain.usecase.RecordSessionEffortUseCase
import com.titan.core.domain.usecase.SetAutoBackupEnabledUseCase
import com.titan.core.domain.usecase.StartPlannedSessionUseCase
import com.titan.core.domain.usecase.StartSessionUseCase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject
import javax.inject.Provider

/**
 * That every use case a ViewModel injects can actually be built by the real graph.
 *
 * The same gap [RepositoryBindingsTest] fills, one layer up: Dagger reports a missing
 * binding only when something reaches it, and the things that reach these live in
 * `:feature:*` modules whose own tests drive the ViewModel directly and never touch
 * Hilt. Without this, a use case gaining a constructor parameter would compile, pass
 * every test, and fail on the device the first time the screen was opened.
 *
 * Injected as [Provider]s so resolution is what is asserted rather than construction at
 * field-injection time — and because these are deliberately unscoped, so there is no
 * identity to assert on the way the repository bindings have.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class, manifest = Config.NONE)
class UseCaseBindingsTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var startSession: Provider<StartSessionUseCase>

    @Inject
    lateinit var startPlannedSession: Provider<StartPlannedSessionUseCase>

    @Inject
    lateinit var applyTemplate: Provider<ApplyTemplateUseCase>

    @Inject
    lateinit var templateSummaries: Provider<GetTemplateSummariesUseCase>

    @Inject
    lateinit var exportBackup: Provider<ExportUseCase>

    // Not `import`: Hilt generates Java for field injection, where it is a keyword.
    @Inject
    lateinit var importBackup: Provider<ImportUseCase>

    @Inject
    lateinit var recordEffort: Provider<RecordSessionEffortUseCase>

    @Inject
    lateinit var setAutoBackupEnabled: Provider<SetAutoBackupEnabledUseCase>

    @Before
    fun inject() {
        hilt.inject()
    }

    @Test
    fun `the session lifecycle use cases resolve`() {
        assertNotNull(startSession.get())
        assertNotNull(startPlannedSession.get())
    }

    @Test
    fun `the template read use cases resolve`() {
        assertNotNull(applyTemplate.get())
        assertNotNull(templateSummaries.get())
    }

    /**
     * Backup reaches further into the graph than any other use case — the row-level repository,
     * the settings store, the draft file and the in-memory session state — and #38's screen is
     * the only thing that will ever ask for it. A missing binding here would surface as a crash
     * the first time somebody tried to back their phone up.
     */
    @Test
    fun `recording a session's effort resolves`() {
        assertNotNull(recordEffort.get())
    }

    @Test
    fun `the backup use cases resolve`() {
        assertNotNull(exportBackup.get())
        assertNotNull(importBackup.get())
    }

    /**
     * The auto-backup toggle, which reaches across two of `:core:data`'s own edges: the
     * schedule store and the WorkManager-backed scheduler. Neither is touched until somebody
     * flips the switch, so without this the first proof that the graph can build it would be a
     * tap on a device.
     */
    @Test
    fun `the auto-backup toggle resolves`() {
        assertNotNull(setAutoBackupEnabled.get())
    }
}
