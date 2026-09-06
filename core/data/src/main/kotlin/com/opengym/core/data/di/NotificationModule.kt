package com.opengym.core.data.di

import com.opengym.core.data.notification.AndroidNotificationPermission
import com.opengym.core.data.notification.AndroidNotifier
import com.opengym.core.domain.notification.NotificationPermission
import com.opengym.core.domain.notification.Notifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The two notification contracts, bound to the implementations that know about Android.
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
}
