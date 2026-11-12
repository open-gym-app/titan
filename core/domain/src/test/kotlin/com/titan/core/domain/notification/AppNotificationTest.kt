package com.titan.core.domain.notification

import com.titan.core.domain.model.SessionRef
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
        // The rest are the announcement's defaults, and every one of them is the loud option:
        // a notification nobody has said anything about is heard, is not a countdown, opens
        // the app wherever it was, and carries no buttons. Each has to be asked for.
        assertFalse(notification.silent)
        assertNull(notification.deepLink)
        assertNull(notification.countdownTo)
        assertEquals(emptyList<NotificationAction>(), notification.actions)
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

    @Test
    fun `a button carries a target rather than a callback`() {
        // The press can arrive at a process started from cold to receive it, with nothing left
        // of the moment the notification was posted — so what the button does has to be a
        // value that survives the round trip out to the system and back.
        val skip = NotificationAction(
            label = "Skip rest",
            target = NotificationTarget.Command(NotificationCommand.SkipRest(sessionId = 7)),
        )

        assertEquals("Skip rest", skip.label)
        assertEquals(NotificationTarget.Command(NotificationCommand.SkipRest(7)), skip.target)
    }

    @Test
    fun `a button that opens a screen and one that does not are different targets`() {
        // The platform's own split: only one of the two may bring the app to the front from
        // the background, and a button meaning "do this and stay out of my way" must be the
        // other one.
        val open = NotificationTarget.Screen(deepLink = "titan://session/7")
        val finish = NotificationTarget.Command(NotificationCommand.FinishSession(SessionRef(7, createdAt = 100)))

        assertEquals("titan://session/7", open.deepLink)
        assertEquals(NotificationCommand.FinishSession(SessionRef(7, createdAt = 100)), finish.command)
        assertNotEquals(NotificationAction("Finish", open), NotificationAction("Finish", finish))
    }

    @Test
    fun `every command names the session it acts on`() {
        // Two workouts can never be in progress at once, but a notification outlives the one
        // it was posted for: a press arriving after the session it named has been finished
        // elsewhere must be refused rather than applied to whatever is running now.
        assertNotEquals(NotificationCommand.SkipRest(7), NotificationCommand.SkipRest(8))
        assertEquals(7L, NotificationCommand.SkipRest(7).sessionId)
        assertEquals(7L, NotificationCommand.FinishSession(SessionRef(7, createdAt = 100)).session.sessionId)
        assertEquals(7L, NotificationCommand.DiscardSession(SessionRef(7, createdAt = 100)).session.sessionId)
        // The two that end a workout also carry when it began, which is what tells a session
        // apart from another one written back under the same id by a restore.
        assertNotEquals(
            NotificationCommand.FinishSession(SessionRef(7, createdAt = 100)),
            NotificationCommand.FinishSession(SessionRef(7, createdAt = 200)),
        )
    }

    @Test
    fun `a countdown notification is posted once and ticked by the platform`() {
        val rest = AppNotification(
            kind = NotificationKind.REST_TIMER,
            title = "Resting",
            body = "Bench press, set 3 next",
            ongoing = true,
            silent = true,
            deepLink = "titan://session/7",
            countdownTo = 1_700_000_090_000,
            actions = listOf(SKIP_REST),
        )

        // Silent because it is re-posted on every adjustment the user makes, and a channel
        // loud enough to be heard across a gym would announce each of those; the expiry that
        // follows is a separate post with the sound back on.
        assertTrue(rest.silent)
        assertEquals(1_700_000_090_000, rest.countdownTo)
        assertEquals("titan://session/7", rest.deepLink)
        assertEquals(listOf(SKIP_REST), rest.actions)
    }

    private companion object {
        val SKIP_REST = NotificationAction(
            label = "Skip rest",
            target = NotificationTarget.Command(NotificationCommand.SkipRest(7)),
        )
    }
}
