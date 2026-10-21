package com.titan.core.data.di

import com.titan.core.domain.time.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The wall clock, as a graph edge.
 *
 * The domain's use cases take [Clock] as a *defaulted* constructor parameter and are
 * therefore wired without one — see [SessionUseCaseModule]. A ViewModel cannot do that:
 * Dagger does not read Kotlin defaults, so a screen that needs to know what day it is has to
 * be handed one.
 *
 * And screens do need to know. "Today" decides which row of the history list wears the
 * accent, which cell of the calendar is ringed, and where a "last 7 days" filter starts — all
 * of which a test has to be able to pin, and none of which it could if the screen reached for
 * [System.currentTimeMillis] itself.
 *
 * Bound to [Clock.SYSTEM] and unscoped, per ARCHITECTURE.md's table: it holds no state, and a
 * fresh reference per injection site costs nothing. A test replaces it the way it replaces
 * any other binding, or — for the ViewModel tests, which construct their subject directly —
 * simply passes another one.
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    @Provides
    fun provideClock(): Clock = Clock.SYSTEM
}
