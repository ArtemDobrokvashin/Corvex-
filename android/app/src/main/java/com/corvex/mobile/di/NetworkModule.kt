package com.corvex.mobile.di

import com.corvex.mobile.data.network.AuthCookieJar
import com.corvex.mobile.data.network.AuthInterceptor
import com.corvex.mobile.data.network.BaseUrlInterceptor
import com.corvex.mobile.data.security.SecureStorage
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideCertificatePinner(secureStorage: SecureStorage): CertificatePinner {
        val builder = CertificatePinner.Builder()
        val host = secureStorage.serverUrl?.removePrefix("https://")?.removePrefix("http://")?.substringBefore("/")?.substringBefore(":")
        val pin = secureStorage.pinnedCertSha256
        if (!host.isNullOrBlank() && !pin.isNullOrBlank()) {
            builder.add(host, pin)
        }
        return builder.build()
    }

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
}
