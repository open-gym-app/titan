package com.titan.core.data.notification

import android.content.Context
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.app.NotificationManagerCompat
import com.titan.core.domain.notification.NotificationPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the system will carry a notification, asked of the system every time.
 *
 * Nothing is cached, and that is the whole design: the answer changes in system settings,
 * while the app is not running, with no callback and no broadcast to tell us. A value read
 * once at startup would be wrong for the rest of the process for every user who ever turned
 * notifications off — which is exactly the user this class exists to detect.
 *
 * `areNotificationsEnabled` rather than a `checkSelfPermission` on `POST_NOTIFICATIONS`,
 * because the permission is only one of the two ways to be switched off and it does not exist
 * below Android 13. The compat call folds both into one question that has a correct answer on
 * every version this app runs on: it reads the runtime grant on 13+, and the app's
 * notification switch everywhere.
 */
@Singleton
class AndroidNotificationPermission @Inject constructor(@param:ApplicationContext private val context: Context) :
    NotificationPermission {

    override fun areNotificationsAllowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    // Annotated so lint can follow the version check through this property rather than
    // flagging every guarded API call behind it as unguarded — the check is a library's, and
    // a library's version checks are invisible to a consumer's lint without this.
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    override val isRuntimePermissionRequired: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
