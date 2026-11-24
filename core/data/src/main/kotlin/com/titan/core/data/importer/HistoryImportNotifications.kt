package com.titan.core.data.importer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.ForegroundInfo
import com.titan.core.data.R
import com.titan.core.domain.importer.HistoryImportProgress
import com.titan.core.domain.navigation.DeepLinks
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.NumberFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What an import from another app shows outside the app: the progress notification a
 * foreground worker must carry, and one line when it ends while nobody is looking.
 *
 * ### Its own channel, outside `NotificationKind`
 *
 * `NotificationKind` is the set of things the user turns on and off in settings. This is not
 * one of them: Android requires a foreground worker to show a notification, so there is nothing
 * to switch off — only how loud it is, which is the channel's business and the user's in system
 * settings. The channel is low importance and silent: an import is something the user started
 * a moment ago, and it should not buzz to say it is still going.
 *
 * ### Both notifications open the import screen
 *
 * Through `titan://settings/import` ([DeepLinks.HISTORY_IMPORT]), the same address-space every
 * other notification uses, so the report is one tap from the line announcing it.
 */
@Singleton
class HistoryImportNotifications @Inject constructor(@param:ApplicationContext private val context: Context) {

    /** The channel, for `NotificationChannels.register` to create with the others. */
    fun channel(): NotificationChannelCompat =
        NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel_history_import))
            .setDescription(context.getString(R.string.notification_channel_history_import_description))
            .setVibrationEnabled(false)
            .setSound(null, null)
            .build()

    /**
     * The foreground notification for [progress], with [cancel] as its stop button.
     *
     * Typed `dataSync` from Android 10, which is what the manifest declares for WorkManager's
     * foreground service: the import reads a file and writes it into the database.
     */
    fun foregroundInfo(
        progress: HistoryImportProgress,
        cancel: PendingIntent,
    ): ForegroundInfo {
        val notification = progressNotification(progress, cancel)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    /**
     * Says the import ended, when the import screen is not open to say it. [succeeded] picks the
     * sentence; the screen behind the tap says the rest. Posted only where notifications are
     * allowed; a denied permission costs this line and nothing else.
     */
    @SuppressLint("MissingPermission") // Checked: `areNotificationsEnabled` is the permission on 33+.
    fun showFinished(
        succeeded: Boolean,
        workoutsImported: Int,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val title = context.getString(
            if (succeeded) R.string.history_import_notification_done else R.string.history_import_notification_failed,
        )
        val body = if (succeeded) {
            context.resources.getQuantityString(
                R.plurals.history_import_notification_done_workouts,
                workoutsImported,
                count(workoutsImported),
            )
        } else {
            context.getString(R.string.history_import_notification_failed_body)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openImportScreen())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        // A refused post is a notification not shown, never an import that failed.
        runCatching { manager.notify(FINISHED_NOTIFICATION_ID, notification) }
    }

    /** Removes [showFinished]'s line, once the screen has shown what it said. */
    fun clearFinished() {
        NotificationManagerCompat.from(context).cancel(FINISHED_NOTIFICATION_ID)
    }

    private fun progressNotification(
        progress: HistoryImportProgress,
        cancel: PendingIntent,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.history_import_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openImportScreen())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        when (progress) {
            is HistoryImportProgress.Writing ->
                builder
                    .setContentText(
                        context.getString(
                            R.string.history_import_notification_writing,
                            count(progress.workoutsWritten),
                            count(progress.workoutsTotal),
                        ),
                    )
                    .setProgress(progress.workoutsTotal, progress.workoutsWritten, false)
                    .addAction(NO_ACTION_ICON, context.getString(R.string.history_import_notification_cancel), cancel)

            // The history is already saved by now, so the button says what pressing it costs:
            // the photos not yet stored, and nothing else.
            is HistoryImportProgress.StoringPhotos ->
                builder
                    .setContentText(
                        context.getString(
                            R.string.history_import_notification_photos,
                            count(progress.photosStored),
                            count(progress.photosTotal),
                        ),
                    )
                    .setProgress(progress.photosTotal, progress.photosStored, false)
                    .addAction(
                        NO_ACTION_ICON,
                        context.getString(R.string.history_import_notification_stop_photos),
                        cancel,
                    )

            else ->
                builder
                    .setContentText(context.getString(R.string.history_import_notification_reading))
                    .setProgress(0, 0, true)
                    .addAction(NO_ACTION_ICON, context.getString(R.string.history_import_notification_cancel), cancel)
        }
        return builder.build()
    }

    private fun openImportScreen(): PendingIntent =
        PendingIntent.getActivity(
            context,
            OPEN_REQUEST_CODE,
            Intent(Intent.ACTION_VIEW, DeepLinks.HISTORY_IMPORT.toUri()).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** A count in the device's digits and grouping. `%d` would write neither. */
    private fun count(value: Int): String = NumberFormat.getIntegerInstance().format(value)

    companion object {

        /** The channel's id. Persisted by the system, so a literal — see `NotificationChannels`. */
        const val CHANNEL_ID: String = "history_import"

        /** Clear of `NotificationKind`'s ids, which count up from one. */
        private const val PROGRESS_NOTIFICATION_ID = 7_201
        private const val FINISHED_NOTIFICATION_ID = 7_202

        /** Clear of the deep-link and command request codes `AndroidNotifier` uses. */
        private const val OPEN_REQUEST_CODE = 7_203

        /** Actions are drawn as text from Android 7; the icon is ignored. */
        private const val NO_ACTION_ICON = 0
    }
}
