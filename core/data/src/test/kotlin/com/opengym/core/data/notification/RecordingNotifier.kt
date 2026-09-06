package com.opengym.core.data.notification

import com.opengym.core.domain.notification.AppNotification
import com.opengym.core.domain.notification.NotificationKind
import com.opengym.core.domain.notification.NotificationOutcome
import com.opengym.core.domain.notification.Notifier

/**
 * A [Notifier] that writes down what it was asked to do instead of doing it.
 *
 * `AndroidNotifier` is tested against a real `NotificationManager` elsewhere; what the tests
 * using this one assert is *which* notification the rest timer composed and when — its
 * chronometer, its buttons, whether it was silent — and reading that off a value is both
 * shorter and stricter than digging it back out of a posted `Notification`'s extras.
 *
 * Posts and cancellations share one ordered log, because the question these tests keep asking is
 * which of the two happened *last*: an expiry that posts over an ongoing note and an expiry that
 * takes it down are the same two calls in the opposite order.
 */
class RecordingNotifier(
    /** What [post] returns. Set to a suppression to stand in for a preference turned off. */
    var outcome: NotificationOutcome = NotificationOutcome.POSTED,
) : Notifier {

    /** Every post and every cancellation, oldest first. */
    val log = mutableListOf<Event>()

    /** Everything actually posted, oldest first. */
    val posted: List<AppNotification> get() = log.filterIsInstance<Event.Posted>().map { it.notification }

    /** The notification on screen: the last post, unless a cancellation came after it. */
    val showing: AppNotification? get() = (log.lastOrNull() as? Event.Posted)?.notification

    override suspend fun post(notification: AppNotification): NotificationOutcome {
        if (outcome == NotificationOutcome.POSTED) log += Event.Posted(notification)
        return outcome
    }

    override suspend fun cancel(
        kind: NotificationKind,
        tag: String?,
    ) {
        log += Event.Cancelled(kind)
    }

    /** One thing the notifier was asked to do. */
    sealed interface Event {
        data class Posted(val notification: AppNotification) : Event

        data class Cancelled(val kind: NotificationKind) : Event
    }
}
