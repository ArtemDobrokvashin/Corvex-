package com.corvex.mobile.data.network.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Обмен с chat-service.
 *
 * Имена и типы сверены с `ChatDtos.java` и `ChatController.java`: это не
 * догадка. Например, история приходит объектом `MessagePage`, а не массивом,
 * и у комнаты есть `lastMessage` и `unreadCount`.
 */
@Serializable
data class ChatMessageDto(
    val id: String,
    val roomId: String,
    val authorId: String,
    val kind: String = "TEXT",
    val bodyMarkdown: String? = null,
    val threadRootId: String? = null,
    val fileRef: String? = null,
    val pinned: Boolean = false,
    val editedAt: String? = null,
    val deleted: Boolean = false,
    val clientMessageId: String? = null,
    val mentionedUserIds: List<String> = emptyList(),
    val createdAt: String? = null
)

@Serializable
data class ChatMessagePageDto(
    val items: List<ChatMessageDto> = emptyList(),
    val hasMore: Boolean = false,
    val nextBefore: String? = null
)

/**
 * Оболочка STOMP-события комнаты.
 *
 * На `/topic/rooms.{id}` приходит не сообщение, а конверт с `type`:
 * `MESSAGE_CREATED`, `MESSAGE_EDITED`, `TYPING`, `READ`. Разбирать его как
 * `ChatMessageDto` нельзя — у конверта нет `id` и `authorId` на верхнем
 * уровне, и десериализация упала бы на каждом сообщении.
 */
@Serializable
data class ChatEventDto(
    val type: String? = null,
    val roomId: String? = null,
    val message: ChatMessageDto? = null,
    val actorId: String? = null,
    val messageId: String? = null,
    val occurredAt: String? = null
)

@Serializable
data class ChatRoomDto(
    val id: String,
    val kind: String = "GROUP",
    val title: String? = null,
    val ownerId: String? = null,
    val readOnly: Boolean = false,
    val memberIds: List<String> = emptyList(),
    val myRole: String? = null,
    val memberCount: Int = 0,
    val unreadCount: Int = 0,
    val mentionCount: Int = 0,
    val lastMessage: ChatMessageDto? = null,
    val lastMessageAt: String? = null
)

@Serializable
data class SendChatMessageRequest(
    val bodyMarkdown: String,
    val clientMessageId: String,
    @SerialName("threadRootId") val threadRootId: String? = null,
    val mentionedUserIds: List<String> = emptyList()
)

interface ChatApi {
    @GET("chat/rooms")
    suspend fun rooms(): Response<List<ChatRoomDto>>

    @GET("chat/rooms/{roomId}/messages")
    suspend fun messages(
        @Path("roomId") roomId: String,
        @Query("before") before: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<ChatMessagePageDto>

    @POST("chat/rooms/{roomId}/messages")
    suspend fun send(
        @Path("roomId") roomId: String,
        @Body request: SendChatMessageRequest
    ): Response<ChatMessageDto>
}
