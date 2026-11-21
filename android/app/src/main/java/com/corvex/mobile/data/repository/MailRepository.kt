package com.corvex.mobile.data.repository

import com.corvex.mobile.data.local.dao.MailDao
import com.corvex.mobile.data.local.entity.FolderEntity
import com.corvex.mobile.data.local.entity.MailEntity
import com.corvex.mobile.data.network.api.MailApi
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MailRepository @Inject constructor(
    private val mailApi: MailApi,
    private val mailDao: MailDao
) {
    fun observeFolders(): Flow<List<FolderEntity>> = mailDao.observeFolders()

    fun observeMails(folderId: String): Flow<List<MailEntity>> = mailDao.observeMails(folderId)

    suspend fun syncFolders() {
        val response = mailApi.getFolders()
        if (response.isSuccessful) {
            val entities = response.body().orEmpty().map {
                FolderEntity(
                    id = it.id,
                    name = it.name,
                    parentId = it.parentId,
                    unreadCount = it.unreadCount
                )
            }
            mailDao.upsertFolders(entities)
        }
    }

    suspend fun syncMessages(folderId: String, cursorUid: Long? = null) {
        val response = mailApi.getMessages(folderId = folderId, cursorUid = cursorUid)
        if (response.isSuccessful) {
            val entities = response.body().orEmpty().map {
                MailEntity(
                    id = it.id,
                    folderId = folderId,
                    uid = it.uid,
                    subject = it.subject,
                    sender = it.sender,
                    snippet = it.snippet,
                    isRead = it.isRead,
                    isStarred = it.isStarred,
                    hasAttachments = it.hasAttachments,
                    receivedAt = it.receivedAt
                )
            }
            mailDao.upsertMails(entities)
        }
    }
}
