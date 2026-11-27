package com.corvex.mobile.domain.chat

/** Момент времени в миллисекундах: на экране нужна только дата. */
typealias Instant = Long

/** Комната чата в том виде, в котором её показывает список. */
data class ChatRoom(
    val id: String,
    val title: String,
    val isDirect: Boolean,
    val unreadCount: Int,
    val lastMessageText: String?,
    val lastMessageAt: Instant,
    val memberCount: Int,
    /** Объявление: писать нельзя, только читать. */
    val isReadOnly: Boolean = false
)

/**
 * Сообщение.
 *
 * `authorId` — UUID, а не имя: имена живут в auth-service, и подтягивать их
 * для каждого сообщения означало бы десятки запросов. В пузыре показываем
 * только «своё/чужое», а имена авторов — отдельной задачей.
 */
data class ChatMessage(
    val id: String,
    val roomId: String,
    val authorId: String,
    val text: String,
    val createdAt: Instant,
    val isMine: Boolean,
    val isDeleted: Boolean = false,
    val clientMessageId: String? = null,
    /** Отправлено, но подтверждения от сервера ещё нет. */
    val isPending: Boolean = false
)

