package com.titan.core.domain.usecase

import com.titan.core.domain.notification.NotificationPermission
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the system permission prompt should go up, and the memory of having raised it.
 *
 * The three refusals are the point. Each of them is a case where raising the prompt does
 * nothing visible and costs something real: on Android 12 there is no prompt at all, after a
 * refusal Android answers for the user, and with the permission already granted there is
 * nothing to ask about. A screen that asked anyway would be waiting on a callback it never
 * gets.
 */
class RequestNotificationPermissionUseCaseTest {

    private val preferences = FakeUserPreferencesRepository()
    private val permission = FakeNotificationPermission()

    private val requestPermission = RequestNotificationPermissionUseCase(preferences, permission)

    @Test
    fun `a fresh install on Android 13 is asked, once`() =
        runTest {
            assertTrue(requestPermission())

            // The second call is the one that matters: Android shows the prompt once and then
            // answers for the user, so asking again is how an app trains somebody to refuse.
            assertFalse(requestPermission())
        }

    @Test
    fun `the asking is recorded before the answer arrives`() =
        runTest {
            requestPermission()

            // A user who swipes the prompt away has still been asked. Recording on the *answer*
            // would let their next tap raise it again.
            assertTrue(preferences.observe().first().notifications.permissionRequested)
        }

    @Test
    fun `nothing is asked when notifications already get through`() =
        runTest {
            permission.allowed = true

            assertFalse(requestPermission())
            assertFalse(preferences.observe().first().notifications.permissionRequested)
        }

    @Test
    fun `nothing is asked on a version with no runtime permission`() =
        runTest {
            // Android 12 and below. The permission is granted at install and a refusal there is
            // the user having switched notifications off by hand, which a prompt cannot undo —
            // the settings screen sends them to system settings instead.
            permission.runtimePermissionRequired = false

            assertFalse(requestPermission())
            assertFalse(preferences.observe().first().notifications.permissionRequested)
        }

    /** Whatever the system is currently saying, as the two answers the domain asks for. */
    private class FakeNotificationPermission(
        var allowed: Boolean = false,
        var runtimePermissionRequired: Boolean = true,
    ) : NotificationPermission {
        override fun areNotificationsAllowed(): Boolean = allowed

        override val isRuntimePermissionRequired: Boolean get() = runtimePermissionRequired
    }
}
