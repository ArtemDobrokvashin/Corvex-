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

@Serializable
data class CalendarEventDto(
    val id: String? = null,
    val title: String,
    val description: String? = null,
    val location: String? = null,
    val startsAt: String,
    val endsAt: String,
    val allDay: Boolean = false,
    val timeZone: String = "UTC",
    val recurrence: String = "none",
    val revision: Long = 0
)

interface CalendarApi {
    @GET("calendar/events")
    suspend fun getEvents(
        @Query("from") fromIso: String,
        @Query("to") toIso: String
    ): Response<List<CalendarEventDto>>

    @POST("calendar/events")
    suspend fun createEvent(@Body event: CalendarEventDto): Response<CalendarEventDto>

    @PUT("calendar/events/{id}")
    suspend fun updateEvent(@Path("id") id: String, @Body event: CalendarEventDto): Response<CalendarEventDto>

    @DELETE("calendar/events/{id}")
    suspend fun deleteEvent(@Path("id") id: String, @Query("revision") revision: Long? = null): Response<Unit>
}
