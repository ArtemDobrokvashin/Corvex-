package com.corvex.mobile.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val unreadCount: Int = 0
)

@Entity(tableName = "mails")
data class MailEntity(
    @PrimaryKey val id: String,
    val folderId: String,
    val uid: Long,
    val subject: String,
    val sender: String,
    val snippet: String,
    val bodyHtml: String? = null,
    val bodyText: String? = null,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val hasAttachments: Boolean = false,
    val receivedAt: Long
)
