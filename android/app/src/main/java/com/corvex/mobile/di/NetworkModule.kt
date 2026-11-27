package com.corvex.mobile.di

import com.corvex.mobile.data.network.AuthCookieJar
import com.corvex.mobile.data.network.AuthInterceptor
import com.corvex.mobile.data.network.BaseUrlInterceptor
import com.corvex.mobile.data.network.api.AuthApi
import com.corvex.mobile.data.network.api.ChatApi
import com.corvex.mobile.data.network.api.MailApi
import com.corvex.mobile.data.security.TokenStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

object PinningHelper {
    fun buildCertificatePinner(serverUrl: String?, pinnedSpkiSha256: String?): CertificatePinner {
        if (pinnedSpkiSha256.isNullOrBlank()) {
            return CertificatePinner.DEFAULT
        }
        val host = serverUrl?.toHttpUrlOrNull()?.host
            ?: throw IllegalArgumentException("Invalid server URL for certificate pinning")
        return CertificatePinner.Builder()
            .add(host, pinnedSpkiSha256.trim())
            .build()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Клиент для WebSocket.
     *
     * Два отличия от обычного:
     *
     *  - `readTimeout = 0` — иначе OkHttp разорвёт соединение через 30 секунд
     *    тишины, и чат будет переподключаться бесконечно;
     *  - **без** [BaseUrlInterceptor]. Он подменяет схему на схему сервера, а
     *    сервер — это `https`; `wss://...` превратился бы в `https://...`, и
     *    `newWebSocket` упал бы с «Expected URL scheme 'http' or 'https'».
     *    Адрес WebSocket StompClient собирает сам.
     */
    @Provides
    @Singleton
    @Named("StreamingClient")
    fun provideStreamingOkHttpClient(
        authInterceptor: AuthInterceptor,
        cookieJar: AuthCookieJar,
        certificatePinner: CertificatePinner
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .cookieJar(cookieJar)
        .certificatePinner(certificatePinner)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Provides
    @Singleton
    fun provideCertificatePinner(tokenStore: TokenStore): CertificatePinner =
        PinningHelper.buildCertificatePinner(tokenStore.serverUrl, tokenStore.pinnedSpkiSha256)

    @Provides
    @Singleton
    @Named("RefreshClient")
    fun provideRefreshOkHttpClient(
        baseUrlInterceptor: BaseUrlInterceptor,
        cookieJar: AuthCookieJar,
        certificatePinner: CertificatePinner
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(baseUrlInterceptor)
        .cookieJar(cookieJar)
        .certificatePinner(certificatePinner)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        baseUrlInterceptor: BaseUrlInterceptor,
        authInterceptor: AuthInterceptor,
        cookieJar: AuthCookieJar,
        certificatePinner: CertificatePinner
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(baseUrlInterceptor)
        .addInterceptor(authInterceptor)
        .cookieJar(cookieJar)
        .certificatePinner(certificatePinner)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://localhost/api/v1/") // Динамически подменяется BaseUrlInterceptor
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    // Retrofit-интерфейсы Hilt не создаёт сам: у них нет @Inject-конструктора.
    // Без этих провайдеров граф падает на MissingBinding для AuthRepository и
    // MailRepository. CalendarApi никто не инжектит, поэтому провайдера нет:
    // мёртвый бин в графе — лишний шум, а не задел на будущее.
    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideMailApi(retrofit: Retrofit): MailApi = retrofit.create(MailApi::class.java)

    @Provides
    @Singleton
    fun provideChatApi(retrofit: Retrofit): ChatApi = retrofit.create(ChatApi::class.java)
}
