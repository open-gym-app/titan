package com.opengym.core.domain.notification

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the interface promises, as opposed to what any one implementation does.
 *
 * `AndroidNotifierTest` in `:core:data` proves the two gates against the real platform, and
 * that is where the behaviour lives. What is asserted here is the part of the contract a
 * second implementation — a test double, or whatever ends up posting on a watch — would have
 * to keep for the four call sites to go on working: a refusal comes back as a value rather
 * than as a thrown exception, and cancelling without naming a tag means the untagged
 * notification rather than every notification of the kind.
 */
class NotifierTest {

    private val notifier = RecordingNotifier()

    @Test
    fun `every refusal comes back as an outcome rather than as an exception`() =
        runTest {
            // Neither refusal is exceptional: both are the normal state of most kinds on most
            // installs, so a caller with nothing else to do about it can ignore the result.
            NotificationOutcome.entries.forEach { outcome ->
                notifier.outcome = outcome

                assertEquals(outcome, notifier.post(REST_OVER))
            }
        }

    @Test
    fun `cancelling without a tag takes back the untagged notification`() =
        runTest {
            notifier.cancel(NotificationKind.SESSION)

            assertEquals(listOf(NotificationKind.SESSION to null), notifier.cancelled)
        }

    @Test
    fun `a tagged notification is taken back by its own tag`() =
        runTest {
            notifier.cancel(NotificationKind.REMINDERS, tag = "monday")

            assertEquals(listOf(NotificationKind.REMINDERS to "monday"), notifier.cancelled)
        }

    /** A [Notifier] that posts nothing and remembers what it was asked to do. */
    private class RecordingNotifier : Notifier {

        var outcome: NotificationOutcome = NotificationOutcome.POSTED

        val cancelled = mutableListOf<Pair<NotificationKind, String?>>()

        override suspend fun post(notification: AppNotification): NotificationOutcome = outcome

        override suspend fun cancel(
            kind: NotificationKind,
            tag: String?,
        ) {
            cancelled += kind to tag
        }
    }

    private companion object {
        val REST_OVER = AppNotification(
            kind = NotificationKind.REST_TIMER,
            title = "Rest over",
            body = "Bench press, set 2 of 5",
        )
    }
}
