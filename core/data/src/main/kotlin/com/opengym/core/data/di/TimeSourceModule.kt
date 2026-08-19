package com.opengym.core.data.di

import com.opengym.core.data.time.ElapsedRealtimeTimeSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlin.time.TimeSource

/**
 * The elapsed-time reading, as a graph edge.
 *
 * [ClockModule]'s counterpart, and a separate binding because they answer different questions:
 * that one says what time it is, this one says how long something has been going. A rest timer
 * needs the second and must not be given the first — see [ElapsedRealtimeTimeSource] for the
 * three readings and why only one of them is a rest.
 *
 * Bound for the same reason the clock is: `RestTimerController` takes it as a *defaulted*
 * constructor parameter, which Dagger does not read, and the ViewModel that constructs one has
 * to be handed something a test can replace with virtual time.
 */
@Module
@InstallIn(SingletonComponent::class)
object TimeSourceModule {

    @Provides
    fun provideTimeSource(): TimeSource = ElapsedRealtimeTimeSource
}
