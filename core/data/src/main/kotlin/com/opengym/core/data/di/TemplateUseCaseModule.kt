package com.opengym.core.data.di

import com.opengym.core.domain.repository.TemplateRepository
import com.opengym.core.domain.repository.UserPreferencesRepository
import com.opengym.core.domain.usecase.ApplyTemplateUseCase
import com.opengym.core.domain.usecase.CreateTemplateUseCase
import com.opengym.core.domain.usecase.DeleteTemplateUseCase
import com.opengym.core.domain.usecase.GetTemplateSummariesUseCase
import com.opengym.core.domain.usecase.UpdateTemplateUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The template use cases the app has a consumer for.
 *
 * Separate from [SessionUseCaseModule] because they are a different concern with a
 * different set of repositories behind them, per ARCHITECTURE.md's rule against a
 * single module of unrelated bindings.
 *
 * The two reads have been here from the start. [CreateTemplateUseCase] joined them with
 * #27's "save as template", and template authoring (#92) supplied the callers the last two
 * were waiting for: the templates tab renames, duplicates and deletes, and the editor
 * rewrites. The rule being followed throughout is "a binding nothing injects is an
 * unverified claim that the wiring works" — every use case here now has a consumer.
 *
 * See [SessionUseCaseModule] for why the use cases are wired from `:core:data` at all,
 * and why these are `@Provides` rather than constructor injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object TemplateUseCaseModule {

    @Provides
    fun provideApplyTemplateUseCase(templateRepository: TemplateRepository): ApplyTemplateUseCase =
        ApplyTemplateUseCase(templateRepository)

    @Provides
    fun provideCreateTemplateUseCase(templateRepository: TemplateRepository): CreateTemplateUseCase =
        CreateTemplateUseCase(templateRepository)

    @Provides
    fun provideUpdateTemplateUseCase(templateRepository: TemplateRepository): UpdateTemplateUseCase =
        UpdateTemplateUseCase(templateRepository)

    @Provides
    fun provideDeleteTemplateUseCase(templateRepository: TemplateRepository): DeleteTemplateUseCase =
        DeleteTemplateUseCase(templateRepository)

    @Provides
    fun provideGetTemplateSummariesUseCase(
        templateRepository: TemplateRepository,
        userPreferencesRepository: UserPreferencesRepository,
    ): GetTemplateSummariesUseCase =
        GetTemplateSummariesUseCase(
            templateRepository = templateRepository,
            userPreferencesRepository = userPreferencesRepository,
        )
}
