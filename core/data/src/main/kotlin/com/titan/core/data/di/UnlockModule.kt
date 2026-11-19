package com.titan.core.data.di

import com.titan.core.data.repository.UnlockRepositoryImpl
import com.titan.core.domain.repository.SparTally
import com.titan.core.domain.repository.UnlockRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class UnlockModule {

    @Binds
    @Singleton
    abstract fun bindUnlockRepository(impl: UnlockRepositoryImpl): UnlockRepository

    companion object {

        /** No spar has been fought yet: the spar (#292) is what counts them. */
        @Provides
        fun provideSparTally(): SparTally = SparTally { 0 }
    }
}
