package com.corvex.mobile.domain.drive

/** Элемент диска: файл или папка. */
data class DriveNode(
    val id: String,
    val parentId: String?,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long,
    val contentType: String?,
    val trashed: Boolean,
    val updatedAt: Long
)

/** Занято и всего на диске. Квота диска не связана с квотой почты. */
data class DriveUsage(
    val usedBytes: Long,
    val quotaBytes: Long
) {
    val fraction: Float
        get() = if (quotaBytes <= 0) 0f else (usedBytes.toFloat() / quotaBytes).coerceIn(0f, 1f)
}

/** Ссылка на файл. Токен показывается один раз и здесь не хранится. */
data class DriveShare(
    val id: String,
    val nodeId: String,
    val expiresAt: Long?,
    val revoked: Boolean
)
