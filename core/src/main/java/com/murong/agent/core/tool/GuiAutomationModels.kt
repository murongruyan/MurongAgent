package com.murong.agent.core.tool

import kotlinx.serialization.Serializable

/**
 * Cross-platform GUI protocol shared by the Android and desktop implementations.
 *
 * Node ids are intentionally observation-scoped.  A caller must observe again after an
 * action changes the screen instead of treating ids as durable selectors.
 */
@Serializable
data class GuiRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
}

@Serializable
data class GuiNodeSnapshot(
    val id: String,
    val parentId: String? = null,
    val role: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: GuiRect,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val checked: Boolean? = null,
    val password: Boolean = false,
    val visible: Boolean = true
)

@Serializable
data class GuiObservation(
    val success: Boolean = true,
    val target: String,
    val observationId: String,
    val application: String? = null,
    val windowTitle: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val nodes: List<GuiNodeSnapshot> = emptyList(),
    val truncated: Boolean = false,
    val semanticTextRedacted: Boolean = false,
    val source: String,
    val error: String? = null
)

@Serializable
data class GuiScreenshot(
    val mimeType: String,
    val base64Data: String,
    val width: Int,
    val height: Int
)

@Serializable
data class GuiToolResponse(
    val success: Boolean,
    val target: String = "android",
    val action: String,
    val observation: GuiObservation? = null,
    val source: String? = null,
    val message: String? = null,
    val modelResult: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val imageSha256: String? = null,
    val error: String? = null
)
