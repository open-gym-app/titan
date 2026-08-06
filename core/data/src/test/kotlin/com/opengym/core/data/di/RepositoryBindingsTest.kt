package com.opengym.core.data.di

import com.opengym.core.data.repository.ExerciseRepositoryImpl
import com.opengym.core.data.repository.InProgressSessionRepositoryImpl
import com.opengym.core.data.repository.SessionRepositoryImpl
import com.opengym.core.data.repository.SessionStateRepositoryImpl
import com.opengym.core.data.repository.TemplateRepositoryImpl
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.repository.TemplateRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject
import javax.inject.Provider

/**
 * That every domain repository contract resolves to exactly one `:core:data`
 * implementation through the real Hilt graph.
 *
 * Worth a test of its own because nothing else catches a missing binding yet:
 * Dagger only reports one when something reaches it, and no ViewModel exists to
 * reach these. Without this, the first screen wired up in M4 would be where the
 * graph is discovered to be broken.
 *
 * Injected as [Provider]s, deliberately. Resolving the binding is what is being
 * asserted, and resolution happens at compile time; calling `get()` on the
 * Room-backed ones would additionally build the database and run the catalogue
 * seed, which is a DAO test's job and not this one's.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class, manifest = Config.NONE)
class RepositoryBindingsTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject
    lateinit var exerciseRepository: Provider<ExerciseRepository>

    @Inject
    lateinit var sessionRepository: Provider<SessionRepository>

    @Inject
    lateinit var templateRepository: Provider<TemplateRepository>

    @Inject
    lateinit var sessionStateRepository: Provider<SessionStateRepository>

    @Inject
    lateinit var inProgressSessionRepository: Provider<InProgressSessionRepository>

    @Before
    fun inject() {
        hilt.inject()
    }

    @Test
    fun `every repository contract is bound to its data-layer implementation`() {
        assertTrue(exerciseRepository.get() is ExerciseRepositoryImpl)
        assertTrue(sessionRepository.get() is SessionRepositoryImpl)
        assertTrue(templateRepository.get() is TemplateRepositoryImpl)
        assertTrue(sessionStateRepository.get() is SessionStateRepositoryImpl)
        assertTrue(inProgressSessionRepository.get() is InProgressSessionRepositoryImpl)
    }

    @Test
    fun `the session state is one shared instance across the app`() {
        // The binding that would still compile unscoped and be wrong at runtime:
        // two instances mean two disagreeing answers to "is a session running".
        assertSame(sessionStateRepository.get(), sessionStateRepository.get())
    }

    @Test
    fun `the draft store is one shared instance, so its write lock is one lock`() {
        assertSame(inProgressSessionRepository.get(), inProgressSessionRepository.get())
    }
}
