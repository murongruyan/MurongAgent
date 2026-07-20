package com.murong.agent.lan

internal const val LEGACY_DESKTOP_PLATFORM = "windows"

internal fun normalizeDesktopPlatform(value: String): String {
    val normalized = value.trim().lowercase()
    require(normalized in setOf("windows", "darwin", "linux")) { "电脑端系统无效" }
    return normalized
}

internal fun normalizeDesktopArchitecture(value: String): String {
    val normalized = value.trim().lowercase()
    require(normalized.isEmpty() || normalized in setOf("amd64", "arm64")) { "电脑端架构无效" }
    return normalized
}

internal fun desktopPlatformLabel(platform: String?, architecture: String?): String {
    val normalizedPlatform = platform?.trim()?.lowercase()
    val platformLabel = when (normalizedPlatform) {
        "darwin" -> "macOS"
        "linux" -> "Linux"
        "windows" -> "Windows"
        else -> "电脑端"
    }
    val architectureLabel = when (architecture?.trim()?.lowercase()) {
        "amd64" -> if (normalizedPlatform == "darwin") "Intel" else "x64"
        "arm64" -> if (normalizedPlatform == "darwin") "Apple Silicon" else "ARM64"
        else -> null
    }
    return listOfNotNull(platformLabel, architectureLabel).joinToString(" · ")
}
