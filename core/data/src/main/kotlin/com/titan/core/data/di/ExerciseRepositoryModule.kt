package com.titan.core.data.di

import com.titan.core.data.repository.ExerciseRepositoryImpl
import com.titan.core.domain.repository.ExerciseRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the exercise catalogue's domain contract to its Room-backed implementation.
 *
 * One module per repository concern rather than a single `RepositoryModule`, per
 * `ARCHITECTURE.md`: a module named after what it provides is the one a reader
 * opens to answer "what is behind `ExerciseRepository`", and adding a binding to it
 * cannot disturb an unrelated one.
 *
 * `@Binds` rather than `@Provides` because there is no construction logic here —
 * the implementation's dependencies are all injectable, so the mapping is the whole
 * of it. The mappers themselves need no binding at all: they are concrete classes
 * with `@Inject` constructors and no scope, per the scoping table.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExerciseRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindExerciseRepository(impl: ExerciseRepositoryImpl): ExerciseRepository
}
