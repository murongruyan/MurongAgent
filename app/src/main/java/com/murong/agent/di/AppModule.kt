package com.murong.agent.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.config.ProviderBalanceService
import com.murong.agent.core.loop.ChatSessionManager
import com.murong.agent.core.mcp.McpRegistry
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideConfigRepository(
        @ApplicationContext context: Context
    ): ConfigRepository = ConfigRepository(context)

    @Provides
    @Singleton
    fun provideMcpRegistry(
        @ApplicationContext context: Context
    ): McpRegistry = McpRegistry(context)

    @Provides
    @Singleton
    fun provideProviderBalanceService(): ProviderBalanceService = ProviderBalanceService()

    @Provides
    @Singleton
    fun provideChatSessionManager(
        @ApplicationContext context: Context,
        configRepository: ConfigRepository,
        mcpRegistry: McpRegistry
    ): ChatSessionManager = ChatSessionManager(context, configRepository, mcpRegistry)
}
