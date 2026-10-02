package com.titan.core.data.di

import com.titan.core.data.repository.QuestRepositoryImpl
import com.titan.core.domain.repository.QuestRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the quest contract to its Room-backed implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class QuestRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindQuestRepository(impl: QuestRepositoryImpl): QuestRepository
}
