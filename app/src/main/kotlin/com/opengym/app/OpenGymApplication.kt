package com.opengym.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.opengym.core.data.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The composition root's `Application`: it holds the Hilt graph, does the one piece of setup that
 * has to happen before any screen does, and hands WorkManager its configuration.
 *
 * ### Why the channels are registered here
 *
 * A notification channel must exist before a notification is posted to it, and the app has
 * four posters that are not on any one screen's path — a scheduled reminder, a rest timer that
 * fires with the app in the background, a nudge about a session left open. There is no later
 * moment that is reliably before all of them, so it happens at start. Creating a channel that
 * already exists is a no-op that leaves everything the user has changed about it alone, which
 * is what makes doing this on every launch correct rather than merely harmless — see
 * [NotificationChannels].
 *
 * Field injection rather than a constructor: `Application` is instantiated by the platform,
 * and Hilt populates the field during `super.onCreate()`. Reading it before that call is the
 * one way to get this wrong.
 *
 * ### Why WorkManager is configured here at all
 *
 * The auto-backup worker (#181) is a `@HiltWorker`: it is constructed with the export use case
 * and the repositories behind it, which WorkManager's default factory knows nothing about and
 * would fail to instantiate at 3am with no screen open to report it. [HiltWorkerFactory] is
 * what builds it from the graph, and a factory can only be supplied through a `Configuration` —
 * which is why this class implements [Configuration.Provider] even though `:app` owns no
 * background work of its own.
 *
 * ### On demand, not at launch
 *
 * The manifest removes WorkManager's own `androidx.startup` initializer (see
 * `AndroidManifest.xml`), so nothing is initialised until the first `WorkManager.getInstance`
 * call — which happens when the backup toggle is flipped, or when the OS runs the job. On a
 * launch where neither happens, WorkManager costs nothing. The two halves go together: leaving
 * the default initializer in place would initialise WorkManager with the default factory before
 * this configuration was ever read.
 */
@HiltAndroidApp
class OpenGymApplication :
    Application(),
    Configuration.Provider {

    @Inject
    lateinit var notificationChannels: NotificationChannels

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        notificationChannels.register()
    }
}
