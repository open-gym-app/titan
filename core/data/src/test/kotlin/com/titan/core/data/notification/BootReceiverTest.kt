package com.titan.core.data.notification

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * That the two events which empty the alarm table are actually listened for.
 *
 * The receiver's *body* is one call to [com.titan.core.domain.notification.ReminderScheduler],
 * which `AndroidReminderSchedulerTest` covers; what cannot be covered there and fails silently
 * here is the registration. A `BOOT_COMPLETED` filter with the permission missing, an
 * un-exported receiver the system therefore cannot reach, or a `MY_PACKAGE_REPLACED` action
 * left off all produce the same symptom: reminders that stop after a restart and never say why.
 *
 * These read the merged manifest through the package manager, which is the same thing the
 * system resolves against.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the reboot broadcast resolves to this app`() {
        assertTrue(
            "nothing in this app answers BOOT_COMPLETED",
            receiversFor(Intent.ACTION_BOOT_COMPLETED).isNotEmpty(),
        )
    }

    @Test
    fun `so does an app update, through the same receiver`() {
        // The two are one event as far as this app is concerned — whatever was scheduled is
        // gone — which is why they share a receiver rather than having one each.
        assertEquals(
            receiversFor(Intent.ACTION_BOOT_COMPLETED).map { it.activityInfo.name },
            receiversFor(Intent.ACTION_MY_PACKAGE_REPLACED).map { it.activityInfo.name },
        )
    }

    @Test
    fun `the receiver is exported, because the sender is the system`() {
        // The one receiver in this module that is. An un-exported one would never hear either
        // broadcast, and nothing would report that: the setting stays on and the alarms stay
        // gone.
        val receiver = receiversFor(Intent.ACTION_BOOT_COMPLETED).single()

        assertEquals(BootReceiver::class.java.name, receiver.activityInfo.name)
        assertTrue(receiver.activityInfo.exported)
    }

    @Test
    fun `the boot permission is declared`() {
        // `BOOT_COMPLETED` is delivered only to apps holding it. It is install-time, so there
        // is nothing to request — but a filter without it is a filter that never fires.
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()

        assertTrue(android.Manifest.permission.RECEIVE_BOOT_COMPLETED in permissions)
    }

    private fun receiversFor(action: String) =
        context.packageManager.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
}
