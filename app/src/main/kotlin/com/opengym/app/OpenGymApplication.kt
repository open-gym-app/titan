package com.opengym.app

import android.app.Application
import com.opengym.core.data.notification.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * The application object, which exists to hold the Hilt graph and to do the one piece of setup
 * that has to happen before any screen does.
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
 */
@HiltAndroidApp
class OpenGymApplication : Application() {

    @Inject
    lateinit var notificationChannels: NotificationChannels

    override fun onCreate() {
        super.onCreate()
        notificationChannels.register()
    }
}
