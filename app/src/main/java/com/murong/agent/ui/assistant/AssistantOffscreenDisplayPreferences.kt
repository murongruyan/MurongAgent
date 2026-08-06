package com.murong.agent.ui.assistant

import android.content.Context

internal data class AssistantOffscreenDisplaySettings(
    /** Long edge in pixels. Zero means use the isolated display's original size. */
    val maxLongEdge: Int = 0,
    /** Zero means follow the physical display's reported refresh rate. */
    val targetFps: Int = 0,
    val jpegQuality: Int = 92,
    /** Target transport bandwidth in Mbps. Zero disables adaptive bandwidth limiting. */
    val targetBitrateMbps: Int = 0,
) {
    fun normalized(): AssistantOffscreenDisplaySettings = copy(
        maxLongEdge = maxLongEdge.takeIf { it == 0 || it in 480..4_096 } ?: 0,
        targetFps = targetFps.takeIf { it == 0 || it in 10..240 } ?: 0,
        jpegQuality = jpegQuality.coerceIn(55, 100),
        targetBitrateMbps = targetBitrateMbps.takeIf { it == 0 || it in 2..240 } ?: 0,
    )
}

internal object AssistantOffscreenDisplayPreferences {
    private const val PREFS = "assistant_offscreen_display"
    private const val KEY_MAX_LONG_EDGE = "max_long_edge"
    private const val KEY_TARGET_FPS = "target_fps"
    private const val KEY_JPEG_QUALITY = "jpeg_quality"
    private const val KEY_TARGET_BITRATE_MBPS = "target_bitrate_mbps"

    fun read(context: Context): AssistantOffscreenDisplaySettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AssistantOffscreenDisplaySettings(
            maxLongEdge = prefs.getInt(KEY_MAX_LONG_EDGE, 0),
            targetFps = prefs.getInt(KEY_TARGET_FPS, 0),
            jpegQuality = prefs.getInt(KEY_JPEG_QUALITY, 92),
            targetBitrateMbps = prefs.getInt(KEY_TARGET_BITRATE_MBPS, 0),
        ).normalized()
    }

    fun write(context: Context, settings: AssistantOffscreenDisplaySettings) {
        val value = settings.normalized()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_LONG_EDGE, value.maxLongEdge)
            .putInt(KEY_TARGET_FPS, value.targetFps)
            .putInt(KEY_JPEG_QUALITY, value.jpegQuality)
            .putInt(KEY_TARGET_BITRATE_MBPS, value.targetBitrateMbps)
            .apply()
    }
}
