package com.titan.core.data.di

import android.content.Context
import com.titan.core.data.marketplace.AssetBundleOpener
import com.titan.core.data.marketplace.BundleOpener
import com.titan.core.data.marketplace.LocalBundleIngestionStrategy
import com.titan.core.data.repository.MarketplaceIngestionStoreImpl
import com.titan.core.data.repository.MarketplaceRepositoryImpl
import com.titan.core.domain.marketplace.MarketplaceIngestionStrategy
import com.titan.core.domain.repository.MarketplaceIngestionStore
import com.titan.core.domain.repository.MarketplaceRepository
import com.titan.core.domain.time.Clock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MarketplaceModule {

    @Provides
    @Singleton
    fun provideMarketplaceRepository(impl: MarketplaceRepositoryImpl): MarketplaceRepository = impl

    @Provides
    @Singleton
    fun provideIngestionStore(impl: MarketplaceIngestionStoreImpl): MarketplaceIngestionStore = impl

    @Provides
    @Singleton
    fun provideBundleOpener(@ApplicationContext context: Context): BundleOpener = AssetBundleOpener(context)

    @Provides
    @Singleton
    fun provideIngestionStrategy(
        opener: BundleOpener,
        store: MarketplaceIngestionStore,
        clock: Clock,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): MarketplaceIngestionStrategy = LocalBundleIngestionStrategy(opener, store, clock, dispatcher)
}
