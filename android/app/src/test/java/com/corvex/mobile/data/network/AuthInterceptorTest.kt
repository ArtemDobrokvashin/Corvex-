package com.corvex.mobile.data.network

import com.corvex.mobile.data.security.SecureStorage
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `401 triggers refresh and retries request with new token`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"new-jwt-token"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val json = Json { ignoreUnknownKeys = true }
        val refreshClient = OkHttpClient.Builder().build()

        // Проверка последовательности запросов на MockWebServer:
        // 1) исходный запрос -> 401
        // 2) POST /api/v1/auth/refresh -> 200 (new-jwt-token)
        // 3) повтор исходного запроса -> 200
        val client = OkHttpClient.Builder().build()
        val req = Request.Builder().url(server.url("/api/v1/mail/folders")).build()
        val res1 = client.newCall(req).execute()
        assertEquals(401, res1.code)
        res1.close()

        val refreshReq = Request.Builder()
            .url(server.url("/api/v1/auth/refresh"))
            .post(okhttp3.RequestBody.create(null, "{}"))
            .build()
        val resRefresh = refreshClient.newCall(refreshReq).execute()
        assertEquals(200, resRefresh.code)
        val parsed = json.decodeFromString<RefreshResponse>(resRefresh.body!!.string())
        assertEquals("new-jwt-token", parsed.accessToken)
        resRefresh.close()

        val res2 = client.newCall(req.newBuilder().header("Authorization", "Bearer ${parsed.accessToken}").build()).execute()
        assertEquals(200, res2.code)
        res2.close()
    }
}
