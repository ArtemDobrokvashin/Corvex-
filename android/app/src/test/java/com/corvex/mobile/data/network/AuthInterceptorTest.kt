package com.corvex.mobile.data.network

import com.corvex.mobile.data.security.TokenStore
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

private class FakeTokenStore(
    override var serverUrl: String? = null,
    override var accessToken: String? = null,
    override var refreshCookieName: String? = null,
    override var refreshCookieValue: String? = null,
    override var refreshCookiePath: String? = null,
    override var refreshCookieExpiresAtMs: Long? = null,
    override var pinnedSpkiSha256: String? = null
) : TokenStore {
    override fun clearAuth() {
        accessToken = null
        refreshCookieName = null
        refreshCookieValue = null
        refreshCookiePath = null
        refreshCookieExpiresAtMs = null
    }
}

class AuthInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = FakeTokenStore(
            serverUrl = server.url("/").toString(),
            accessToken = "old-jwt"
        )
        val json = Json { ignoreUnknownKeys = true }
        val refreshClient = OkHttpClient.Builder().build()
        val interceptor = AuthInterceptor(tokenStore, refreshClient, json)
        client = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `adds bearer to normal requests and skips login`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        client.newCall(Request.Builder().url(server.url("/api/v1/mail/folders")).build()).execute().close()
        client.newCall(Request.Builder().url(server.url("/api/v1/auth/login")).build()).execute().close()

        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        assertEquals("Bearer old-jwt", req1.getHeader("Authorization"))
        assertNull(req2.getHeader("Authorization"))
    }

    @Test
    fun `401 triggers single refresh without bearer and retries request`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"new-jwt"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val res = client.newCall(Request.Builder().url(server.url("/api/v1/mail/folders")).build()).execute()
        assertEquals(200, res.code)
        res.close()

        val first = server.takeRequest()
        val refresh = server.takeRequest()
        val retried = server.takeRequest()

        assertEquals("Bearer old-jwt", first.getHeader("Authorization"))
        assertEquals("/api/v1/auth/refresh", refresh.path)
        assertNull(refresh.getHeader("Authorization"))
        assertEquals("Bearer new-jwt", retried.getHeader("Authorization"))
        assertEquals("new-jwt", tokenStore.accessToken)
    }

    @Test
    fun `failed refresh clears auth`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val res = client.newCall(Request.Builder().url(server.url("/api/v1/mail/folders")).build()).execute()
        assertEquals(401, res.code)
        res.close()
        assertNull(tokenStore.accessToken)
    }

    @Test
    fun `two parallel 401 trigger only one refresh`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accessToken":"shared-new-jwt"}"""))
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))

        val pool = Executors.newFixedThreadPool(2)
        val call = Callable {
            client.newCall(Request.Builder().url(server.url("/api/v1/mail/folders")).build()).execute().use { it.code }
        }
        val f1 = pool.submit(call)
        val f2 = pool.submit(call)
        assertEquals(200, f1.get())
        assertEquals(200, f2.get())
        pool.shutdown()

        var refreshCount = 0
        repeat(5) {
            val r = server.takeRequest()
            if (r.path == "/api/v1/auth/refresh") refreshCount++
        }
        assertEquals(1, refreshCount)
    }
}
