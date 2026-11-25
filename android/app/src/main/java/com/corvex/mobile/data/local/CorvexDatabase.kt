package com.corvex.mobile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.corvex.mobile.data.local.dao.MailDao
import com.corvex.mobile.data.local.entity.FolderEntity
import com.corvex.mobile.data.local.entity.MailEntity

@Database(
    entities = [FolderEntity::class, MailEntity::class],
    version = 1,
    // Схема пока не экспортируется: каталога для неё нет, и Room писал
    // предупреждение на каждой сборке. Выключено осознанно — как только появится
    // первая миграция, включить обратно и задать `room.schemaLocation`, иначе
    // миграцию нечем проверить.
    exportSchema = false
)
abstract class CorvexDatabase : RoomDatabase() {
    abstract fun mailDao(): MailDao
}
