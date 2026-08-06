package com.opengym.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * Marks the dispatcher for work that blocks on IO.
 *
 * A qualifier rather than a direct reference to [Dispatchers.IO] at the call site,
 * because a hard-coded dispatcher is a dependency a test cannot replace: a class
 * that names [Dispatchers.IO] itself always runs its work on the real IO pool, so a
 * test of anything timing-dependent has to wait in real time instead of controlling
 * the clock. Injecting it leaves that seam open.
 *
 * Room does not need this — it dispatches its own suspend queries — so the only
 * bindings that ask for it are the ones doing their own IO.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
