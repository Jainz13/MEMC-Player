package com.jainz.memc.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class, VideoEntity::class, MovieEntity::class,
        ShowEntity::class, SeasonEntity::class, EpisodeMetaEntity::class,
    ],
    version = 1,
    // Off: the phone and TV variants build side by side and were both writing the same schema file.
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun videos(): VideoDao
    abstract fun meta(): MetaDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "library.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
