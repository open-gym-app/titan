package com.titan.core.data.notification

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.titan.core.domain.model.UserPreferences
import com.titan.core.domain.notification.NotificationKind
import com.titan.core.domain.notification.NotificationPreferences
import com.titan.core.domain.notification.ReminderPreferences
import com.titan.core.domain.time.Clock
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * The reminder as the operating system keeps it: up to seven standing alarms.
 *
 * Asserted against Robolectric's own `AlarmManager` rather than against a mock, because the
 * claims worth testing are all about what is *pending*: seven alarms for seven days, one fewer
 * when a day is cleared, and none at all when the switch is off. Only the scheduler can be
 * asked whether anything is scheduled, and "turning the reminder off cancels every pending
 * alarm" is one of #39's acceptance criteria stated exactly that way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidReminderSchedulerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)
    private val preferences = FakeUserPreferences(ON_MWF_AT_SIX)

    private val scheduler = AndroidReminderScheduler(context, preferences, Clock { MONDAY_MORNING })

    private var systemZone: TimeZone = TimeZone.getDefault()

    /**
     * The scheduler resolves the schedule against `ZoneId.systemDefault()`, so the zone has to
     * be pinned or every expected instant here is a fact about the machine running the build.
     * Robolectric pins the locale and not this.
     */
    @Before
    fun pinTimeZone() {
        systemZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(PARIS))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(systemZone)
    }

    @Test
    fun `an enabled schedule arms one alarm per chosen day`() =
        runTest {
            scheduler.sync()

            assertEquals(3, scheduledAlarms().size)
        }

    @Test
    fun `each alarm is set for that day's own next occurrence`() =
        runTest {
            scheduler.sync()

            // Monday tonight, Wednesday and Friday later in the week — the whole reason this is
            // seven wall-clock alarms rather than one repeating one, which could only express a
            // fixed interval.
            assertEquals(
                listOf(
                    at(MONDAY, 18, 0),
                    at(MONDAY.plusDays(2), 18, 0),
                    at(MONDAY.plusDays(4), 18, 0),
                ),
                scheduledAlarms().map { it.triggerAtMs }.sorted(),
            )
        }

    @Test
    fun `clearing a day takes its alarm down and leaves the others standing`() =
        runTest {
            scheduler.sync()

            preferences.setReminderDay(DayOfWeek.WEDNESDAY, enabled = false)
            scheduler.sync()

            assertEquals(
                listOf(at(MONDAY, 18, 0), at(MONDAY.plusDays(4), 18, 0)),
                scheduledAlarms().map { it.triggerAtMs }.sorted(),
            )
        }

    @Test
    fun `turning the reminder off cancels every pending alarm`() =
        runTest {
            scheduler.sync()

            preferences.setNotificationEnabled(NotificationKind.REMINDERS, enabled = false)
            scheduler.sync()

            // The acceptance criterion, as `dumpsys alarm | grep titan` would report it.
            assertEquals(emptyList<Long>(), scheduledAlarms().map { it.triggerAtMs })
        }

    @Test
    fun `a schedule with no days left arms nothing, without turning the switch off`() =
        runTest {
            DayOfWeek.entries.forEach { preferences.setReminderDay(it, enabled = false) }

            scheduler.sync()

            assertEquals(emptyList<Long>(), scheduledAlarms().map { it.triggerAtMs })
        }

    @Test
    fun `syncing twice does not double the alarms`() =
        runTest {
            // Every write in the settings screen calls `sync`, and the alarm itself calls it as
            // it re-arms. Fourteen alarms for a three-day schedule would be a phone woken four
            // times too often, and nothing would report it.
            scheduler.sync()
            scheduler.sync()

            assertEquals(3, scheduledAlarms().size)
        }

    @Test
    fun `a rescheduled day moves rather than joining what was there`() =
        runTest {
            scheduler.sync()

            preferences.setReminderTime(LocalTime.of(7, 0))
            scheduler.sync()

            // Monday 07:00 has already gone by the time this is scheduled, so Monday's alarm is
            // next week's — which is exactly the case a naive "keep the earlier one" would get
            // wrong.
            assertEquals(
                listOf(
                    at(MONDAY.plusDays(2), 7, 0),
                    at(MONDAY.plusDays(4), 7, 0),
                    at(MONDAY.plusWeeks(1), 7, 0),
                ),
                scheduledAlarms().map { it.triggerAtMs }.sorted(),
            )
        }

    @Test
    fun `exactness is read from the system rather than assumed`() {
        // What the settings screen renders "may arrive late" from. The failure to rule out is
        // claiming a punctuality the device is not offering: a reminder that lands at 18:11
        // with no explanation reads as a broken app.
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(scheduler.isExact())

        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(scheduler.isExact())
    }

    @Test
    fun `a device that refuses exact alarms still gets its reminders`() =
        runTest {
            ShadowAlarmManager.setCanScheduleExactAlarms(false)

            scheduler.sync()

            // Late rather than absent — the acceptance criterion for a revoked exact-alarm
            // permission. `setAndAllowWhileIdle` still pierces Doze; what it gives up is the
            // minute.
            assertEquals(3, scheduledAlarms().size)
        }

    /** Everything pending, which for this class is everything this scheduler set. */
    private fun scheduledAlarms() = shadowOf(alarms).scheduledAlarms

    private companion object {
        val PARIS: ZoneId = ZoneId.of("Europe/Paris")

        /** An ordinary Monday, clear of either daylight-saving boundary. */
        val MONDAY: LocalDate = LocalDate.of(2026, 4, 27)

        val MONDAY_MORNING: Long = at(MONDAY, 9, 0)

        val ON_MWF_AT_SIX = UserPreferences(
            notifications = NotificationPreferences(enabled = setOf(NotificationKind.REMINDERS)),
            reminders = ReminderPreferences(),
        )

        fun at(
            date: LocalDate,
            hour: Int,
            minute: Int,
        ): Long = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(PARIS).toInstant().toEpochMilli()
    }
}
