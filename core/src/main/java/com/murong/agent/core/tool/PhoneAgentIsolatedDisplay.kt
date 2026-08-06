package com.murong.agent.core.tool

/** Metadata for a privileged, isolated Android display used by Phone Agent. */
data class PhoneAgentIsolatedDisplayInfo(
    val displayId: Int,
    val width: Int,
    val height: Int,
)

/**
 * App-module bridge for a Shizuku/Root-owned virtual display.
 *
 * The core module owns no privilege framework. Android app wiring may provide this implementation;
 * otherwise Phone Agent continues to use the physical display and accessibility service.
 */
interface PhoneAgentIsolatedDisplaySession {
    fun isAvailable(): Boolean
    suspend fun start(): PhoneAgentIsolatedDisplayInfo
    suspend fun currentPackageName(): String?
    suspend fun launchPackage(packageName: String): Boolean
    suspend fun launchViewUri(packageName: String, uri: String): Boolean
    suspend fun launchShareText(packageName: String, text: String): Boolean
    suspend fun captureScreenshot(): GuiScreenshot
    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean
    suspend fun key(keyCode: String): Boolean
    suspend fun typeText(text: String): Boolean
    suspend fun close()
}
