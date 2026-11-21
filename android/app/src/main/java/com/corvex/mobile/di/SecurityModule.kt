package com.corvex.mobile.di

import com.corvex.mobile.data.security.SecureStorage
import com.corvex.mobile.data.security.TokenStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    @Binds
    @Singleton
    abstract fun bindTokenStore(impl: SecureStorage): TokenStore
}
