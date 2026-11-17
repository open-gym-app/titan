package com.titan.core.data.di

import com.titan.core.data.db.TitanDatabase
import com.titan.core.data.db.dao.PantryDao
import com.titan.core.data.db.dao.PetDao
import com.titan.core.data.db.dao.PetStateDao
import com.titan.core.data.db.dao.PetUnlockDao
import com.titan.core.data.db.dao.SessionPetClaimDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The Titans milestone's DAOs, beside [DatabaseModule]'s, over the same database. */
@Module
@InstallIn(SingletonComponent::class)
object TitanDaoModule {

    @Provides
    @Singleton
    fun providePetDao(db: TitanDatabase): PetDao = db.petDao()

    @Provides
    @Singleton
    fun providePetStateDao(db: TitanDatabase): PetStateDao = db.petStateDao()

    @Provides
    @Singleton
    fun providePantryDao(db: TitanDatabase): PantryDao = db.pantryDao()

    @Provides
    @Singleton
    fun providePetUnlockDao(db: TitanDatabase): PetUnlockDao = db.petUnlockDao()

    @Provides
    @Singleton
    fun provideSessionPetClaimDao(db: TitanDatabase): SessionPetClaimDao = db.sessionPetClaimDao()
}
