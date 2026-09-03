package com.opengym.core.data.di

import com.opengym.core.data.repository.QuestRepositoryImpl
import com.opengym.core.domain.repository.QuestRepository
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
