package com.murong.agent.core.codex

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import com.murong.agent.common.process.PersistentJsonLineProcess
import com.murong.agent.common.toolchain.ToolchainManager
import java.io.File

enum class CodexAppServerExecutableKind {
    DEDICATED_APP_SERVER,
    CODEX_CLI,
}

data class CodexAppServerCommand(
    val argv: List<String>,
    val kind: CodexAppServerExecutableKind,
)

/** Pure command selection kept separate so Android-free tests can pin CLI semantics. */
object CodexAppServerCommandResolver {
    fun resolve(
        dedicatedAppServerPath: String?,
        codexCliPath: String?,
    ): CodexAppServerCommand {
        val dedicated = dedicatedAppServerPath?.trim().orEmpty()
        if (dedicated.isNotEmpty()) {
            return CodexAppServerCommand(
                argv = listOf(dedicated),
                kind = CodexAppServerExecutableKind.DEDICATED_APP_SERVER,
            )
        }

        val cli = codexCliPath?.trim().orEmpty()
        if (cli.isNotEmpty()) {
            return CodexAppServerCommand(
                argv = listOf(cli, "app-server"),
                kind = CodexAppServerExecutableKind.CODEX_CLI,
            )
        }

        throw CodexAppServerUnavailableException(
            "终端扩展包缺少 codex-app-server 或 codex。请安装含 Codex 的终端扩展包（1.10 或更高版本）后重启应用。",
        )
    }
}

class CodexAppServerUnavailableException(message: String) : IllegalStateException(message)

/**
 * Resolves the extension toolchain and creates a fresh process transport for
 * every start/restart. Authentication files are owned exclusively by Codex in
 * [codexHome]; this class never opens or inspects auth.json.
 */
internal class AndroidCodexAppServerTransportFactory(
    context: Context,
    workingDirectory: File?,
) : CodexAppServerTransportFactory {
    private val appContext = context.applicationContext
    private val workingDirectory = workingDirectory

    override fun create(): CodexAppServerTransport {
        val command = CodexAppServerCommandResolver.resolve(
            dedicatedAppServerPath = ToolchainManager.findCommandPath(
                DEDICATED_COMMAND,
                appContext,
            ),
            codexCliPath = ToolchainManager.findCommandPath(CLI_COMMAND, appContext),
        )
        // The official dedicated ARM64 app-server release is a static ET_EXEC
        // ELF. It can execute from the extension APK directly, but Android's
        // linker rejects ET_EXEC when PRoot's guest launcher wraps it with
        // `/system/bin/linker64` ("unexpected e_type: 2"). Run that dedicated
        // server directly; PRoot remains available for a future dynamic Codex
        // CLI fallback.
        val packageCompatible = command.kind == CodexAppServerExecutableKind.CODEX_CLI &&
            ToolchainManager.hasRelocatablePackageManager(appContext)
        val launchArgv = if (packageCompatible) {
            ToolchainManager.buildPackageCompatibleCommand(command.argv, appContext)
        } else {
            command.argv
        }
        val codexHome = File(appContext.filesDir, CODEX_HOME_DIRECTORY)
        check(codexHome.isDirectory || codexHome.mkdirs()) {
            "Cannot create private Codex home"
        }
        val cwd = workingDirectory
            ?.takeIf { it.isDirectory }
            ?: appContext.filesDir
        // A VPN may publish a local HTTP proxy in LinkProperties without setting
        // Android's legacy global proxy. Prefer that numeric endpoint: the static
        // Linux app-server can reach it without DNS, and the VPN owns DNS/routing.
        // The narrow Java CONNECT bridge remains a fallback for ordinary networks.
        val networkProxyUrl = activeNetworkProxyUrl()
        val loopbackProxy = if (
            command.kind == CodexAppServerExecutableKind.DEDICATED_APP_SERVER &&
            networkProxyUrl == null
        ) {
            CodexLoopbackHttpsProxy.start()
        } else {
            null
        }
        try {
            val builder = ProcessBuilder(launchArgv)
                .directory(cwd)
                .redirectErrorStream(false)
            if (packageCompatible) {
                ToolchainManager.applyPackageCompatibleEnvironment(builder.environment(), appContext)
            } else {
                ToolchainManager.applyProcessEnvironment(builder.environment(), appContext)
                // Do not expose fixed-prefix Termux executables to a direct static
                // server: those need PRoot. Codex can use Android's system shell
                // and direct file operations until a native Android CLI is offered.
                builder.environment()["PATH"] = ToolchainManager.buildSystemPath()
                builder.environment().remove("LD_PRELOAD")
                builder.environment().remove("TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE")
            }
            (networkProxyUrl ?: loopbackProxy?.proxyUrl)?.let { proxyUrl ->
                // reqwest honours both spellings. The proxy only CONNECTs to the
                // OpenAI/ChatGPT allowlist when bridged, and leaves TLS end-to-end
                // encrypted in both cases. The network proxy is supplied by the
                // active Android VPN/network, not by an app text field.
                builder.environment()["HTTP_PROXY"] = proxyUrl
                builder.environment()["HTTPS_PROXY"] = proxyUrl
                builder.environment()["ALL_PROXY"] = proxyUrl
                builder.environment()["http_proxy"] = proxyUrl
                builder.environment()["https_proxy"] = proxyUrl
                builder.environment()["all_proxy"] = proxyUrl
                // The child connects to the local bridge before it reaches its
                // final OpenAI destination, so an inherited wildcard NO_PROXY
                // must not cause Codex to bypass the bridge.
                builder.environment().remove("NO_PROXY")
                builder.environment().remove("no_proxy")
            }
            val caBundle = File(
                ToolchainManager.ensureInstalled(appContext).rootDir,
                "etc/tls/cert.pem",
            )
            if (caBundle.isFile) {
                // The official Linux-musl executable cannot consume Android's
                // platform root store. The extension ships the standard PEM
                // bundle; Codex adds it to its normal trust store rather than
                // weakening TLS validation.
                builder.environment()["CODEX_CA_CERTIFICATE"] = caBundle.absolutePath
            }
            builder.environment()["CODEX_HOME"] = codexHome.absolutePath

            return PersistentCodexAppServerTransport(
                process = PersistentJsonLineProcess(processBuilder = builder),
                onProcessStopped = { loopbackProxy?.close() },
            )
        } catch (error: Throwable) {
            loopbackProxy?.close()
            throw error
        }
    }

    private fun activeNetworkProxyUrl(): String? {
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
            ?: return null
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val proxyInfo = connectivityManager.getLinkProperties(activeNetwork)?.httpProxy ?: return null
        // PAC URLs require evaluating JavaScript and cannot be converted safely to
        // one static proxy endpoint. Let the loopback bridge handle that case.
        if (proxyInfo.pacFileUrl != Uri.EMPTY) return null
        return CodexNetworkProxyUrl.fromNumericHost(proxyInfo.host, proxyInfo.port)
    }

    companion object {
        const val DEDICATED_COMMAND = "codex-app-server"
        const val CLI_COMMAND = "codex"
        const val CODEX_HOME_DIRECTORY = "codex-home"
    }
}

/** Android-free normalization so system-proxy selection stays unit-testable. */
internal object CodexNetworkProxyUrl {
    fun fromNumericHost(host: String?, port: Int): String? {
        val normalizedHost = host?.trim()?.removeSurrounding("[", "]").orEmpty()
        if (normalizedHost.isEmpty() || port !in 1..65_535) return null
        val isIpv4 = normalizedHost.split('.').let { octets ->
            octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }
        }
        val isIpv6 = ':' in normalizedHost
        if (!isIpv4 && !isIpv6) return null
        val hostForUrl = if (isIpv6) "[$normalizedHost]" else normalizedHost
        return "http://$hostForUrl:$port"
    }
}
