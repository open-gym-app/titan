package com.opengym.core.data.di

import com.opengym.core.data.backup.WorkManagerAutoBackupScheduler
import com.opengym.core.data.repository.AutoBackupRepositoryImpl
import com.opengym.core.data.repository.BackupRepositoryImpl
import com.opengym.core.data.repository.ExerciseRepositoryImpl
import com.opengym.core.data.repository.GamificationRepositoryImpl
import com.opengym.core.data.repository.InProgressSessionRepositoryImpl
import com.opengym.core.data.repository.LocalDataRepositoryImpl
import com.opengym.core.data.repository.PersonalRecordRepositoryImpl
import com.opengym.core.data.repository.SessionRepositoryImpl
import com.opengym.core.data.repository.SessionStateRepositoryImpl
import com.opengym.core.data.repository.TemplateDraftRepositoryImpl
import com.opengym.core.data.repository.TemplateRepositoryImpl
import com.opengym.core.data.repository.UserPreferencesRepositoryImpl
import com.opengym.core.domain.backup.AutoBackupScheduler
import com.opengym.core.domain.repository.AutoBackupRepository
import com.opengym.core.domain.repository.BackupRepository
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.GamificationRepository
import com.opengym.core.domain.repository.InProgressSessionRepository
import com.opengym.core.domain.repository.LocalDataRepository
import com.opengym.core.domain.repository.PersonalRecordRepository
import com.opengym.core.domain.repository.SessionRepository
import com.opengym.core.domain.repository.SessionStateRepository
import com.opengym.core.domain.repository.TemplateDraftRepository
import com.opengym.core.domain.repository.TemplateRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
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
 * Injected as [Provider]s so the scope assertions below can ask the graph for an
 * instance *twice*, which is the only way to tell a `@Singleton` binding from an
 * unscoped one — a plain injected field is handed over once and would make
 * `assertSame` compare a reference with itself.
 *
 * Resolving these costs nothing beyond the graph: `Room.databaseBuilder().build()`
 * does not open the file, so `get()` on the Room-backed repositories constructs
 * them without running a query or the catalogue seed, which is a DAO test's job.
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

    @Inject
    lateinit var templateDraftRepository: Provider<TemplateDraftRepository>

    @Inject
    lateinit var userPreferencesRepository: Provider<UserPreferencesRepository>

    @Inject
    lateinit var localDataRepository: Provider<LocalDataRepository>

    @Inject
    lateinit var personalRecordRepository: Provider<PersonalRecordRepository>

    @Inject
    lateinit var gamificationRepository: Provider<GamificationRepository>

    @Inject
    lateinit var backupRepository: Provider<BackupRepository>

    @Inject
    lateinit var autoBackupRepository: Provider<AutoBackupRepository>

    // Not a repository, but bound the same way and resolvable for the same reason: nothing
    // reaches it until a toggle is flipped, so a missing binding would first be seen on a
    // device. Resolving it touches no WorkManager — see `WorkManagerAutoBackupScheduler`.
    @Inject
    lateinit var autoBackupScheduler: Provider<AutoBackupScheduler>

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
        assertTrue(templateDraftRepository.get() is TemplateDraftRepositoryImpl)
        assertTrue(userPreferencesRepository.get() is UserPreferencesRepositoryImpl)
        assertTrue(localDataRepository.get() is LocalDataRepositoryImpl)
        assertTrue(personalRecordRepository.get() is PersonalRecordRepositoryImpl)
        assertTrue(gamificationRepository.get() is GamificationRepositoryImpl)
        assertTrue(backupRepository.get() is BackupRepositoryImpl)
        assertTrue(autoBackupRepository.get() is AutoBackupRepositoryImpl)
        assertTrue(autoBackupScheduler.get() is WorkManagerAutoBackupScheduler)
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

    @Test
    fun `the preferences store is one shared instance, so there is one DataStore on the file`() {
        // DataStore refuses two live instances over one file, so an unscoped binding here
        // would not merely be wasteful — the second injection site would throw.
        assertSame(userPreferencesRepository.get(), userPreferencesRepository.get())
    }

    @Test
    fun `the auto-backup store is one shared instance, on a file of its own`() {
        // Scoped for the settings store's reason — DataStore refuses two live instances over
        // one file — and a *second* store at all because the schedule is not a user setting:
        // it is device-local, and it must not travel in an export. See `AutoBackupSchedule`.
        assertSame(autoBackupRepository.get(), autoBackupRepository.get())
    }
}
