package com.corvex.mobile.data.network.api

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Календарь (gateway: /api/v1/calendar, mail-storage-service).
 *
 * Время — ISO-8601 instant в UTC, зона — IANA id события. Клиент никогда не
 * пересчитывает момент по зоне браузера: событие, созданное в другой зоне,
 * иначе показывалось бы со сдвигом.
 */
@Serializable
data class CalendarSourceDto(
    val kind: String = "NONE",
    val mailId: String? = null,
    val subject: String? = null,
    val sender: String? = null
)

@Serializable
data class CalendarEventDto(
    val id: String? = null,
    val revision: Long = 0,
    val title: String = "",
    val description: String? = null,
    val location: String? = null,
    val startsAt: String = "",
    val endsAt: String = "",
    val allDay: Boolean = false,
    val timeZone: String = "UTC",
    val priority: String = "medium",
    val busyStatus: String = "busy",
    val recurrence: String = "none",
    val reminderMinutes: Int? = null,
    val attendees: List<String> = emptyList(),
    val source: CalendarSourceDto = CalendarSourceDto(),
    val createdAt: String? = null,
    val updatedAt: String? = null
)

/**
 * Тело создания и изменения.
 *
 * `revision` на сервере обязательна для PUT: без неё второе устройство молча
 * перезаписало бы более новую версию. Поэтому поле есть всегда, а null
 * отправляется только при создании.
 */
@Serializable
data class CalendarEventSaveDto(
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startsAt: String,
    val endsAt: String,
    val allDay: Boolean = false,
    val timeZone: String,
    val priority: String = "medium",
    val busyStatus: String = "busy",
    val recurrence: String = "none",
    val reminderMinutes: Int? = null,
    val attendees: List<String> = emptyList(),
    val source: CalendarSourceDto = CalendarSourceDto(),
    val revision: Long? = null
)

interface CalendarApi {
    @GET("calendar/events")
    suspend fun events(
        @Query("from") fromIso: String,
        @Query("to") toIso: String
    ): Response<List<CalendarEventDto>>

    @GET("calendar/events/{id}")
    suspend fun event(@Path("id") id: String): Response<CalendarEventDto>

    @POST("calendar/events")
    suspend fun create(@Body event: CalendarEventSaveDto): Response<CalendarEventDto>

    @PUT("calendar/events/{id}")
    suspend fun update(
        @Path("id") id: String,
        @Body event: CalendarEventSaveDto
    ): Response<CalendarEventDto>

    @DELETE("calendar/events/{id}")
    suspend fun delete(
        @Path("id") id: String,
        @Query("revision") revision: Long? = null
    ): Response<Unit>
}
