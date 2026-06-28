package com.opengym.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.opengym.core.data.db.dao.ExerciseDao
import com.opengym.core.data.db.dao.SessionDao
import com.opengym.core.data.db.dao.SetDao
import com.opengym.core.data.db.entity.ExerciseEntity
import com.opengym.core.data.db.entity.SessionEntity
import com.opengym.core.data.db.entity.SetEntity

@Database(
    entities = [ExerciseEntity::class, SessionEntity::class, SetEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class OpenGymDatabase : RoomDatabase() {

    abstract fun exerciseDao(): ExerciseDao
    abstract fun sessionDao(): SessionDao
    abstract fun setDao(): SetDao

    companion object {
        val foreignKeysCallback = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }
    }
}
