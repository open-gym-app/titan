package com.opengym.core.data.notification

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import com.opengym.core.domain.notification.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That every kind has a channel, at the importance the user was promised.
 *
 * Worth asserting rather than eyeballing in system settings, because a channel's importance is
 * fixed the moment it is created: getting one wrong ships a rest timer that cannot make a
 * sound, and no later release can raise it — the system ignores the attempt for every install
 * that already has the channel. The failure is silent, permanent and only visible on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationChannelsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `every kind gets a channel`() {
        NotificationChannels(context).register()

        assertEquals(
            NotificationKind.entries.map { it.channelId }.toSet(),
            manager.notificationChannels.map { it.id }.toSet(),
        )
    }

    @Test
    fun `each channel carries the importance its kind was given`() {
        NotificationChannels(context).register()

        val importances = manager.notificationChannels.associate { it.id to it.importance }

        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, importances["reminders"])
        assertEquals(NotificationManager.IMPORTANCE_HIGH, importances["rest_timer"])
        assertEquals(NotificationManager.IMPORTANCE_LOW, importances["session"])
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, importances["progression"])
    }

    @Test
    fun `only the rest timer makes a noise`() {
        NotificationChannels(context).register()

        val restTimer = manager.getNotificationChannel("rest_timer")
        assertTrue("the rest timer must vibrate", restTimer.shouldVibrate())
        assertNotNull("the rest timer must have a sound", restTimer.sound)

        // The session channel is the one that must stay quiet: it describes a state rather
        // than announcing an event, and it is on screen for as long as the session runs.
        val session = manager.getNotificationChannel("session")
        assertFalse(session.shouldVibrate())
        assertNull(session.sound)
    }

    @Test
    fun `the rest timer's sound is a notification rather than an alarm`() {
        NotificationChannels(context).register()

        // The one place the ringer distinction can be honoured. The alarm stream is not muted
        // by the ringer switch, so `USAGE_ALARM` would have this half of the alert playing a
        // tone on a phone set to vibrate — the one mode `RestAlertStyle` says is silent.
        assertEquals(
            AudioAttributes.USAGE_NOTIFICATION,
            manager.getNotificationChannel("rest_timer").audioAttributes?.usage,
        )
    }

    @Test
    fun `every channel says what it is, in words a user could act on`() {
        NotificationChannels(context).register()

        manager.notificationChannels.forEach { channel ->
            assertTrue("${channel.id} needs a name", channel.name.isNotBlank())
            assertTrue("${channel.id} needs a description", !channel.description.isNullOrBlank())
        }
    }

    @Test
    fun `registering again changes nothing the user has set`() {
        val channels = NotificationChannels(context)
        channels.register()

        // What the user does in system settings: turn the rest timer down. The app has no way
        // to raise it again, and must not try — this runs on every launch.
        manager.getNotificationChannel("rest_timer").let { existing ->
            existing.importance = NotificationManager.IMPORTANCE_LOW
            manager.createNotificationChannel(existing)
        }

        channels.register()

        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel("rest_timer").importance,
        )
        assertEquals(NotificationKind.entries.size, manager.notificationChannels.size)
    }
}
