package com.titan.core.data.db

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.titan.core.data.mapper.SessionExerciseMapper
import com.titan.core.data.mapper.SessionMapper
import com.titan.core.data.mapper.WorkoutSetMapper
import com.titan.core.data.notification.AndroidBackgroundRestTimer
import com.titan.core.data.notification.AndroidIdleSessionMonitor
import com.titan.core.data.notification.AndroidNotifier
import com.titan.core.data.notification.AppForegroundState
import com.titan.core.data.notification.RestTimerBroadcasts
import com.titan.core.data.repository.BackupRepositoryImpl
import com.titan.core.data.repository.SessionRepositoryImpl
import com.titan.core.data.repository.SessionStateRepositoryImpl
import com.titan.core.domain.backup.ExportSink
import com.titan.core.domain.backup.ImportSource
import com.titan.core.domain.backup.model.ImportProgress
import com.titan.core.domain.notification.AppNotification
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationPermission
import com.titan.core.domain.time.Clock
import com.titan.core.domain.timer.BackgroundRest
import com.titan.core.domain.timer.RestSkipRequests
import com.titan.core.domain.timer.RestTimerSnapshot
import com.titan.core.domain.usecase.ExportUseCase
import com.titan.core.domain.usecase.ImportUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration.Companion.seconds

/**
 * A restore and the workout the operating system is still holding for the device it replaces.
 *
 * The rest's note and exact alarm, and the idle nudge's, live outside every store a restore
 * replaces, and they name a session id. After a restore that id is either gone or — since a
 * restore writes back ids this device has used before — a different workout's. Both are asked
 * of Robolectric's own `AlarmManager` and `NotificationManager`, through the real classes that
 * post them and the real `ImportUseCase` over a real SQLite.
 */
class RestoreLeftoversTest : RoomTestBase() {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val notifications: NotificationManager = context.getSystemService(NotificationManager::class.java)

    private val preferences = InMemoryUserPreferencesRepository()
    private val drafts = CountingInProgressSessionRepository()
    private val notifier = AndroidNotifier(context, preferences, NotificationsAllowed)
    private val restTimer = AndroidBackgroundRestTimer(context, notifier, InBackground)

    private val backup by lazy { BackupRepositoryImpl(db, Dispatchers.Unconfined) }
    private val sessions by lazy {
        SessionRepositoryImpl(
            db,
            SessionMapper(),
            SessionExerciseMapper(),
            WorkoutSetMapper(),
        )
    }
    private val idleSessionMonitor by lazy { AndroidIdleSessionMonitor(context, sessions, notifier, Clock { NOW }) }

    private val export by lazy {
        ExportUseCase(backupRepository = backup, userPreferencesRepository = preferences, clock = { NOW })
    }

    private val import by lazy {
        ImportUseCase(
            backupRepository = backup,
            userPreferencesRepository = preferences,
            inProgressSessionRepository = drafts,
            templateDraftRepository = CountingTemplateDraftRepository(),
            sessionStateRepository = SessionStateRepositoryImpl(),
            backgroundRestTimer = restTimer,
            idleSessionMonitor = idleSessionMonitor,
            reminderScheduler = RecordingReminderScheduler(),
        )
    }

    @Before
    fun enableTheNudge() =
        runBlocking {
            preferences.setNotificationEnabled(NotificationKind.SESSION, enabled = true)
        }

    @Test
    fun `a restore takes the rest, the idle nudge and their alarms down`() =
        runBlocking {
            val document = exportToText()
            val sessionId = db.workoutSessionDao().insert(session(createdAt = NOW, lastActiveAt = NOW))
            restTimer.set(runningRest(sessionId, createdAt = NOW))
            idleSessionMonitor.arm()
            notifier.post(AppNotification(kind = NotificationKind.SESSION, title = "Still training?", body = "Nothing"))
            assertTrue("precondition: two alarms pending", shadowOf(alarms).scheduledAlarms.size == 2)

            assertEquals(ImportProgress.Complete::class, restore(document)::class)

            assertEquals(emptyList<Any>(), shadowOf(alarms).scheduledAlarms)
            assertEquals(emptyList<String>(), showing())
        }

    /**
     * The restore writes session 7 back as another workout, in progress. A rest-expiry alarm
     * built for the session 7 this device had before is about a workout that is gone, however
     * the number reads now.
     */
    @Test
    fun `an expiry for a session id the restore gave to another workout posts nothing`() =
        runBlocking {
            db.workoutSessionDao().insert(session(createdAt = EARLIER, lastActiveAt = EARLIER).copy(id = REUSED_ID))
            val document = exportToText()
            db.openHelper.writableDatabase.execSQL("DELETE FROM workout_session")
            db.workoutSessionDao().insert(session(createdAt = NOW, lastActiveAt = NOW).copy(id = REUSED_ID))
            restTimer.set(runningRest(REUSED_ID, createdAt = NOW))
            val expiry = pendingExpiry()

            restore(document)
            RestTimerBroadcasts(restTimer, NoSkipRequests, drafts, sessions).handle(expiry)

            assertEquals(emptyList<String>(), showing())
        }

    private suspend fun exportToText(): String {
        val text = StringBuilder()
        export(ExportSink { text.append(it) }, "1.0-test").toList()
        return text.toString()
    }

    private suspend fun restore(document: String): ImportProgress =
        import(ImportSource { document.byteInputStream() }).toList().last()

    private fun runningRest(
        sessionId: Long,
        createdAt: Long,
    ) = BackgroundRest(
        sessionId = sessionId,
        exerciseName = "Bench press",
        setNumber = 3,
        rest = RestTimerSnapshot.Running(deadline = NOW + 90_000, total = 90.seconds),
        sessionCreatedAt = createdAt,
    )

    /**
     * The broadcast the rest's pending alarm would deliver, read off the scheduler.
     *
     * Robolectric 4.14 deprecates the field in favour of an accessor it does not ship yet.
     */
    @Suppress("DEPRECATION")
    private fun pendingExpiry(): Intent {
        val alarm = checkNotNull(shadowOf(alarms).peekNextScheduledAlarm()) { "no rest alarm is pending" }
        return shadowOf(checkNotNull(alarm.operation)).savedIntent
    }

    /** The titles in the shade, which say *which* note was left when one was. */
    private fun showing(): List<String> =
        notifications.activeNotifications.map {
            it.notification.extras.getCharSequence(
                Notification.EXTRA_TITLE,
            ).toString()
        }

    private object NotificationsAllowed : NotificationPermission {
        override fun areNotificationsAllowed(): Boolean = true

        override val isRuntimePermissionRequired: Boolean = true
    }

    private object InBackground : AppForegroundState {
        override suspend fun isInForeground(): Boolean = false
    }

    private object NoSkipRequests : RestSkipRequests {
        override val skipped: Flow<Long> = emptyFlow()

        override suspend fun skip(sessionId: Long) = Unit
    }

    private companion object {
        const val NOW = 1_762_000_000_000L
        const val EARLIER = 1_761_000_000_000L
        const val REUSED_ID = 7L
    }
}
