package com.corvex.mobile.data.network.api

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class FolderDto(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val unreadCount: Int = 0
)

@Serializable
data class MailSummaryDto(
    val id: String,
    val uid: Long,
    val subject: String,
    val sender: String,
    val snippet: String = "",
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val hasAttachments: Boolean = false,
    val receivedAt: Long = 0L
)

interface MailApi {
    @GET("mail/folders")
    suspend fun getFolders(): Response<List<FolderDto>>

    @GET("mail/folders/{id}/messages")
    suspend fun getMessages(
        @Path("id") folderId: String,
        @Query("cursorUid") cursorUid: Long? = null,
        @Query("limit") limit: Int = 30
    ): Response<List<MailSummaryDto>>
}
