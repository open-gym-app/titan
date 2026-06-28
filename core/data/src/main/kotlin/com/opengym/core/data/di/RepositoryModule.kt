package com.opengym.core.data.di

import com.opengym.core.data.repository.ExerciseRepositoryImpl
import com.opengym.core.data.repository.SessionRepositoryImpl
import com.opengym.core.domain.repository.ExerciseRepository
import com.opengym.core.domain.repository.SessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindExerciseRepository(impl: ExerciseRepositoryImpl): ExerciseRepository

    @Binds
    @Singleton
    abstract fun bindSessionRepository(impl: SessionRepositoryImpl): SessionRepository
}
