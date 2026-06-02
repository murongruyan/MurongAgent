package dev.reasonix.mobile.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.reasonix.mobile.core.config.ConfigRepository
import dev.reasonix.mobile.core.config.ProviderBalanceService
import dev.reasonix.mobile.core.loop.ChatSessionManager
import dev.reasonix.mobile.core.mcp.McpRegistry
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
