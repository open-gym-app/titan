package com.titan.core.data.di

import com.titan.core.data.repository.DefaultTemplateCatalogueImpl
import com.titan.core.domain.repository.DefaultTemplateCatalogue
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the templates the app ships with to the definitions the seed writes them from. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DefaultTemplateCatalogueModule {

    @Binds
    abstract fun bindDefaultTemplateCatalogue(impl: DefaultTemplateCatalogueImpl): DefaultTemplateCatalogue
}
