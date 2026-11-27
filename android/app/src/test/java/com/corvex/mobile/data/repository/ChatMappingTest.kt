package com.corvex.mobile.data.repository

import com.corvex.mobile.data.parseInstant
import com.corvex.mobile.data.network.api.ChatMessageDto
import com.corvex.mobile.data.network.api.ChatMessagePageDto
import com.corvex.mobile.data.network.api.ChatEventDto
import com.corvex.mobile.data.network.api.ChatRoomDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор ответов chat-service.
 *
 * Все проверки — по реальным формам из `ChatDtos.java`: `record` без
 * `@JsonProperty` отдаёт camelCase, время — ISO-8601.
 */
class ChatMappingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `room response maps to domain`() {
        val raw = """
            {"id":"11111111-1111-1111-1111-111111111111","kind":"GROUP",
             "title":"Разработка","ownerId":"22222222-2222-2222-2222-222222222222",
             "readOnly":false,"memberIds":["22222222-2222-2222-2222-222222222222"],
             "myRole":"MEMBER","memberCount":5,"unreadCount":3,"mentionCount":0,
             "lastMessage":null,"createdAt":"2026-10-19T09:00:00Z",
             "lastMessageAt":"2026-10-19T10:30:00Z"}
        """.trimIndent()

        val room = json.decodeFromString<ChatRoomDto>(raw).toDomain()

        assertEquals("Разработка", room.title)
        assertEquals(3, room.unreadCount)
        assertEquals(5, room.memberCount)
        assertFalse(room.isReadOnly)
        assertEquals(1_792_405_800_000L, room.lastMessageAt)
    }

    @Test
    fun `untitled direct room still has something to show`() {
        // У личной переписки title может быть null: имя собеседника знает
        // auth-service. Пустая строка в списке выглядела бы как сбой загрузки.
        val raw = """{"id":"1","kind":"DIRECT","title":null,"readOnly":false,"unreadCount":0}"""
        val room = json.decodeFromString<ChatRoomDto>(raw).toDomain()

        assertEquals("Без названия", room.title)
        assertTrue(room.isDirect)
        assertEquals(0L, room.lastMessageAt)
    }

    @Test
    fun `message page maps`() {
        // Сервер отдаёт новые сверху. В ленте чата новые должны быть снизу,
        // поэтому Repository переворачивает список — здесь проверяем порядок
        // после переворота, как его увидит экран.
        val raw = """
            {"items":[
              {"id":"2","roomId":"r","authorId":"a","kind":"TEXT","bodyMarkdown":"второе",
               "createdAt":"2026-10-19T10:01:00Z"},
              {"id":"1","roomId":"r","authorId":"a","kind":"TEXT","bodyMarkdown":"первое",
               "createdAt":"2026-10-19T10:00:00Z"}
            ],"hasMore":true,"nextBefore":"2026-10-19T10:00:00Z"}
        """.trimIndent()

        val page = json.decodeFromString<ChatMessagePageDto>(raw)
        val ordered = page.items.map { it.toDomain() }.reversed()

        assertTrue(page.hasMore)
        assertEquals("первое", ordered.first().text)
        assertEquals("второе", ordered.last().text)
    }

    @Test
    fun `system message without body does not crash`() {
        // bodyMarkdown может быть null у служебных сообщений.
        val raw = """{"id":"1","roomId":"r","authorId":"a","kind":"SYSTEM","bodyMarkdown":null}"""
        val message = json.decodeFromString<ChatMessageDto>(raw).toDomain()

        assertEquals("", message.text)
    }

    @Test
    fun `stomp event is a wrapper around the message`() {
        // Главная ловушка: на /topic/rooms.{id} приходит не MessageView, а
        // ChatEventView. Разбор тела кадра как ChatMessageDto падал бы на
        // каждом сообщении, потому что id лежит внутри message.
        val raw = """
            {"type":"MESSAGE_CREATED","roomId":"11111111-1111-1111-1111-111111111111",
             "message":{"id":"9","roomId":"11111111-1111-1111-1111-111111111111",
                        "authorId":"a","kind":"TEXT","bodyMarkdown":"привет",
                        "createdAt":"2026-10-19T10:00:00Z"},
             "actorId":"a","occurredAt":"2026-10-19T10:00:00Z"}
        """.trimIndent()

        val event = json.decodeFromString<ChatEventDto>(raw)

        assertEquals("MESSAGE_CREATED", event.type)
        assertEquals("привет", event.message?.toDomain()?.text)
    }

    @Test
    fun `typing event carries no message`() {
        // TYPING не должен попадать в ленту: у него сообщения нет вовсе.
        val raw = """{"type":"TYPING","roomId":"r","actorId":"a","occurredAt":"2026-10-19T10:00:00Z"}"""
        val event = json.decodeFromString<ChatEventDto>(raw)

        assertNull(event.message)
    }

    @Test
    fun `timestamps with offset are parsed`() {
        // Часовой пояс сервера может быть не UTC; ISO-8601 с offset тоже
        // обязан разбираться, иначе все новые сообщения оказались бы в 1970.
        assertEquals(1_758_870_000_000L, parseInstant("2025-09-26T10:00:00+03:00"))
        assertEquals(1_758_870_000_000L, parseInstant("2025-09-26T07:00:00Z"))
    }

    @Test
    fun `broken timestamp does not throw`() {
        assertEquals(0L, parseInstant(null))
        assertEquals(0L, parseInstant(""))
        assertEquals(0L, parseInstant("не дата"))
    }
}
