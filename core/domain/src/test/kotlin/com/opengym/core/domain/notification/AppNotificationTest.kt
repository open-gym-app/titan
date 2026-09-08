package com.opengym.core.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a caller gets by leaving the optional half of a notification alone, and what makes two
 * of them the same notification.
 *
 * Neither is idle: `:core:data` derives the platform id from the kind and the tag, so two
 * notifications that compare equal here land on the same id and the second *replaces* the
 * first. That is what three of the four kinds want and none of them ask for — it falls out of
 * the defaults below, which is exactly why changing one would be silent.
 */
class AppNotificationTest {

    @Test
    fun `a notification is one at a time, and dismissible, unless it says otherwise`() {
        val notification = AppNotification(
            kind = NotificationKind.REST_TIMER,
            title = "Rest over",
            body = "Bench press, set 2 of 5",
        )

        assertEquals(NotificationKind.REST_TIMER, notification.kind)
        assertEquals("Rest over", notification.title)
        assertEquals("Bench press, set 2 of 5", notification.body)
        // No tag, so re-posting the kind replaces what is showing rather than stacking a
        // second one under it; not ongoing, so the user can swipe it away.
        assertNull(notification.tag)
        assertFalse(notification.ongoing)
    }

    @Test
    fun `an ongoing notification has to ask to be one`() {
        // Only the session note, and only because a session that is still running is a fact
        // about the app's state rather than an announcement.
        val session = AppNotification(
            kind = NotificationKind.SESSION,
            title = "Session running",
            body = "Started 40 minutes ago",
            ongoing = true,
        )

        assertTrue(session.ongoing)
    }

    @Test
    fun `a tag is what keeps two notifications of one kind apart`() {
        val monday = AppNotification(
            kind = NotificationKind.REMINDERS,
            title = "Push day",
            body = "Today",
            tag = "monday",
        )
        val thursday = monday.copy(tag = "thursday")

        assertNotEquals(monday, thursday)
        assertNotEquals(monday.toString(), thursday.toString())
    }

    @Test
    fun `two notifications with the same words are the same notification`() {
        val first = AppNotification(NotificationKind.PROGRESSION, "Level 12", "Squat is still climbing")
        val second = AppNotification(NotificationKind.PROGRESSION, "Level 12", "Squat is still climbing")

        // Equal, so equal ids in the data layer: the second post overwrites the first rather
        // than telling the user twice.
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
