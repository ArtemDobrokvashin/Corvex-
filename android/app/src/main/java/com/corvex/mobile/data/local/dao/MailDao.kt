package com.corvex.mobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.corvex.mobile.data.local.entity.FolderEntity
import com.corvex.mobile.data.local.entity.MailEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MailDao {
    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(folders: List<FolderEntity>)

    @Query("SELECT * FROM mails WHERE folderId = :folderId ORDER BY uid DESC")
    fun observeMails(folderId: String): Flow<List<MailEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMails(mails: List<MailEntity>)

    @Query("UPDATE mails SET isRead = :isRead WHERE id = :mailId")
    suspend fun updateReadFlag(mailId: String, isRead: Boolean)
}
