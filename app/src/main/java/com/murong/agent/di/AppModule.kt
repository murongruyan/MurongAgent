package com.murong.agent.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.murong.agent.core.config.ConfigRepository
import com.murong.agent.core.config.ProviderBalanceService
import com.murong.agent.core.codex.CodexAppServerClient
import com.murong.agent.core.loop.ChatSessionManager
import com.murong.agent.core.mcp.McpRegistry
import com.murong.agent.backup.MurongBackupManager
import com.murong.agent.lan.LanWebComputerWorkspaceBridge
import com.murong.agent.lan.LanWebCredentialSyncBridge
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
    fun provideMurongBackupManager(
        @ApplicationContext context: Context,
        configRepository: ConfigRepository,
        mcpRegistry: McpRegistry,
        credentialSyncBridge: LanWebCredentialSyncBridge
    ): MurongBackupManager = MurongBackupManager(context, configRepository, mcpRegistry, credentialSyncBridge)

    @Provides
    @Singleton
    fun provideCodexAppServerClient(
        @ApplicationContext context: Context
    ): CodexAppServerClient = CodexAppServerClient(context)

    @Provides
    @Singleton
    fun provideChatSessionManager(
        @ApplicationContext context: Context,
        configRepository: ConfigRepository,
        mcpRegistry: McpRegistry,
        codexAppServerClient: CodexAppServerClient,
        computerWorkspaceBridge: LanWebComputerWorkspaceBridge
    ): ChatSessionManager = ChatSessionManager(
        context = context,
        configRepository = configRepository,
        mcpRegistry = mcpRegistry,
        codexAppServer = codexAppServerClient,
        computerWorkspaceGateway = computerWorkspaceBridge
    )
}
