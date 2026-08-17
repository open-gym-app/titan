package com.opengym.core.data.di

import com.opengym.core.domain.repository.TemplateRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.domain.usecase.ApplyTemplateUseCase
import com.opengym.core.domain.usecase.GetTemplateSummariesUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The template *reads* — listing them for a picker, and reading one out as the plan for
 * a session.
 *
 * Separate from [SessionUseCaseModule] because they are a different concern with a
 * different set of repositories behind them, per ARCHITECTURE.md's rule against a
 * single module of unrelated bindings. The write path (`CreateTemplateUseCase` and its
 * two siblings) has no consumer until template authoring lands (#92) and is deliberately
 * not bound here yet: a binding nothing injects is a compile-time cost and an
 * unverified claim that the wiring works.
 *
 * See [SessionUseCaseModule] for why the use cases are wired from `:core:data` at all,
 * and why these are `@Provides` rather than constructor injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object TemplateUseCaseModule {

    @Provides
    fun provideApplyTemplateUseCase(
        templateRepository: TemplateRepository,
    ): ApplyTemplateUseCase = ApplyTemplateUseCase(templateRepository)

    @Provides
    fun provideGetTemplateSummariesUseCase(
        templateRepository: TemplateRepository,
        userPreferencesRepository: UserPreferencesRepository,
    ): GetTemplateSummariesUseCase = GetTemplateSummariesUseCase(
        templateRepository = templateRepository,
        userPreferencesRepository = userPreferencesRepository,
    )
}
