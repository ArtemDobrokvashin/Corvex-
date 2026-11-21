package com.corvex.mobile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.corvex.mobile.data.local.dao.MailDao
import com.corvex.mobile.data.local.entity.FolderEntity
import com.corvex.mobile.data.local.entity.MailEntity

@Database(
    entities = [FolderEntity::class, MailEntity::class],
    version = 1,
    exportSchema = true
)
abstract class CorvexDatabase : RoomDatabase() {
    abstract fun mailDao(): MailDao
}
