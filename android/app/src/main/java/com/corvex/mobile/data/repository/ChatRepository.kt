package com.corvex.mobile.data.repository

import com.corvex.mobile.data.network.api.ChatApi
import com.corvex.mobile.data.network.api.ChatMessageDto
import com.corvex.mobile.data.network.api.ChatRoomDto
import com.corvex.mobile.data.network.api.SendChatMessageRequest
import com.corvex.mobile.domain.chat.ChatMessage
import com.corvex.mobile.domain.chat.ChatRoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Чат через REST.
 *
 * Зачем REST, если есть WebSocket: историю и список комнат через STOMP не
 * получить — сервер их отдаёт только HTTP. Кроме того, REST-отправка работает,
 * когда WebSocket недоступен (корпоративный прокси режет Upgrade), и сообщение
 * всё равно доходит.
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatApi: ChatApi
) {
    suspend fun rooms(): Result<List<ChatRoom>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = chatApi.rooms()
            if (!response.isSuccessful) error("Не удалось загрузить комнаты: ${response.code()}")
            response.body().orEmpty().map { it.toDomain() }
        }
    }

    suspend fun messages(roomId: String, before: String? = null): Result<List<ChatMessage>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = chatApi.messages(roomId, before)
                if (!response.isSuccessful) error("Не удалось загрузить сообщения: ${response.code()}")
                val page = response.body() ?: error("Пустой ответ сервера")
                // Сервер отдаёт последние сообщения первыми, а в чате читают
                // снизу вверх — переворачиваем, чтобы старые были сверху.
                page.items.map { it.toDomain() }.reversed()
            }
        }

    /**
     * Отправка по REST.
     *
     * `ownUserId` нужен, чтобы отличать свои сообщения от чужих: сервер
     * возвращает `authorId`, но не говорит, кто вы. Сравнение делаем здесь,
     * а не в UI.
     */
    suspend fun send(
        roomId: String,
        text: String,
        ownUserId: String?,
        clientMessageId: String = UUID.randomUUID().toString()
    ): Result<ChatMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val response = chatApi.send(
                roomId,
                SendChatMessageRequest(bodyMarkdown = text, clientMessageId = clientMessageId)
            )
            if (!response.isSuccessful) error("Сообщение не отправлено: ${response.code()}")
            response.body()?.toDomain(ownUserId) ?: error("Пустой ответ сервера")
        }
    }
}

internal fun ChatRoomDto.toDomain(): ChatRoom = ChatRoom(
    id = id,
    title = title?.takeIf { it.isNotBlank() } ?: "Без названия",
    isDirect = kind.equals("DIRECT", ignoreCase = true),
    unreadCount = unreadCount,
    lastMessageText = lastMessage?.bodyMarkdown?.takeIf { it.isNotBlank() },
    lastMessageAt = parseInstant(lastMessageAt ?: lastMessage?.createdAt),
    memberCount = memberCount,
    isReadOnly = readOnly
)

internal fun ChatMessageDto.toDomain(ownUserId: String? = null): ChatMessage = ChatMessage(
    id = id,
    roomId = roomId,
    authorId = authorId,
    // `bodyMarkdown` может быть null у служебных сообщений (системное,
    // «вошёл в комнату»), и падать на этом незачем.
    text = bodyMarkdown.orEmpty(),
    createdAt = parseInstant(createdAt),
    isMine = ownUserId != null && authorId == ownUserId,
    isDeleted = deleted,
    clientMessageId = clientMessageId
)

/**
 * Разбор времени ISO-8601.
 *
 * `Instant.parse` понимает `2026-11-26T10:00:00Z`, но не выдерживает
 * смещения вида `+03:00` без обработки — поэтому `OffsetDateTime` как
 * запасной вариант. Ноль вместо исключения: одно кривое время не должно
 * ронять весь список.
 */
internal fun parseInstant(value: String?): Long {
    if (value.isNullOrBlank()) return 0L
    return runCatching { Instant.parse(value).toEpochMilli() }
        .recoverCatching {
            java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
        }
        .getOrDefault(0L)
}
