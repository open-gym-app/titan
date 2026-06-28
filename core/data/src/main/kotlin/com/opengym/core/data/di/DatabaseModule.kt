package com.opengym.core.data.di

import android.content.Context
import androidx.room.Room
import com.opengym.core.data.db.OpenGymDatabase
import com.opengym.core.data.db.dao.ExerciseDao
import com.opengym.core.data.db.dao.SessionDao
import com.opengym.core.data.db.dao.SetDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OpenGymDatabase =
        Room.databaseBuilder(context, OpenGymDatabase::class.java, "open-gym.db")
            .addCallback(OpenGymDatabase.foreignKeysCallback)
            .build()

    @Provides
    @Singleton
    fun provideExerciseDao(db: OpenGymDatabase): ExerciseDao = db.exerciseDao()

    @Provides
    @Singleton
    fun provideSessionDao(db: OpenGymDatabase): SessionDao = db.sessionDao()

    @Provides
    @Singleton
    fun provideSetDao(db: OpenGymDatabase): SetDao = db.setDao()
}
