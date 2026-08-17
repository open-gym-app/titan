package com.opengym.core.data.di

import com.opengym.core.domain.usecase.ApplyTemplateUseCase
import com.opengym.core.domain.usecase.GetTemplateSummariesUseCase
import com.opengym.core.domain.usecase.StartPlannedSessionUseCase
import com.opengym.core.domain.usecase.StartSessionUseCase
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
}
