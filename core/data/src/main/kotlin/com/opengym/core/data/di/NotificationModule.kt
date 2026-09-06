package com.opengym.core.data.di

import com.opengym.core.data.notification.AndroidBackgroundRestTimer
import com.opengym.core.data.notification.AndroidIdleSessionMonitor
import com.opengym.core.data.notification.AndroidNotificationPermission
import com.opengym.core.data.notification.AndroidNotifier
import com.opengym.core.data.notification.AppForegroundState
import com.opengym.core.data.notification.ProcessForegroundState
import com.opengym.core.data.notification.RestSkipRequestsImpl
import com.opengym.core.domain.notification.NotificationPermission
import com.opengym.core.domain.notification.Notifier
import com.opengym.core.domain.session.IdleSessionMonitor
import com.opengym.core.domain.timer.BackgroundRestTimer
import com.opengym.core.domain.timer.RestSkipRequests
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The notification contracts, bound to the implementations that know about Android.
 *
 * `NotificationChannels` is deliberately absent: it has an `@Inject` constructor and no
 * interface, so the graph can build it without being told how, and giving it a binding here
 * would only be a second place to keep in step.
 *
 * Both bindings are `@Singleton` for the reason the class notes give rather than for speed —
 * `AndroidNotifier` holds the preference read every consumer's gate depends on, and one
 * instance of it is what makes "the single place that checks" literally true.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationModule {

    @Binds
    @Singleton
    abstract fun bindNotifier(impl: AndroidNotifier): Notifier

    @Binds
    @Singleton
    abstract fun bindNotificationPermission(impl: AndroidNotificationPermission): NotificationPermission

    @Binds
    @Singleton
    abstract fun bindBackgroundRestTimer(impl: AndroidBackgroundRestTimer): BackgroundRestTimer

    @Binds
    @Singleton
    abstract fun bindIdleSessionMonitor(impl: AndroidIdleSessionMonitor): IdleSessionMonitor

    /**
     * `@Singleton` and load-bearing: the skip channel is a rendezvous between a broadcast
     * receiver and a ViewModel, and two instances of it are two channels neither of which the
     * other is listening to.
     */
    @Binds
    @Singleton
    abstract fun bindRestSkipRequests(impl: RestSkipRequestsImpl): RestSkipRequests

    @Binds
    @Singleton
    abstract fun bindAppForegroundState(impl: ProcessForegroundState): AppForegroundState
}
