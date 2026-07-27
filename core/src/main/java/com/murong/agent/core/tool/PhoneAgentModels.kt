package com.murong.agent.core.tool

import kotlinx.serialization.Serializable

@Serializable
data class PhoneAgentTaskRequest(
    val task: String,
    val taskType: String = "general",
    val maxSteps: Int? = null,
    val platforms: List<String> = emptyList(),
    val quantity: Int = 1
)

@Serializable
data class PhoneAgentRunResult(
    val success: Boolean,
    val status: String,
    val message: String,
    val stepsExecuted: Int = 0,
    val requiresUserAction: Boolean = false,
    val currentApplication: String? = null,
    val trace: List<PhoneAgentStepRecord> = emptyList(),
    val foodDeliveryComparison: FoodDeliveryComparison? = null
)

@Serializable
data class PhoneAgentStepRecord(
    val step: Int,
    val application: String? = null,
    val action: String,
    val success: Boolean,
    val detail: String? = null
)

@Serializable
data class FoodDeliveryComparison(
    val query: String? = null,
    val offers: List<FoodDeliveryOffer> = emptyList(),
    val cheapestOffer: FoodDeliveryOffer? = null,
    val notes: List<String> = emptyList()
)

@Serializable
data class FoodDeliveryOffer(
    val platform: String,
    val merchant: String? = null,
    val item: String? = null,
    val specification: String? = null,
    val quantity: Int = 1,
    val itemPrice: Double? = null,
    val packingFee: Double? = null,
    val deliveryFee: Double? = null,
    val discount: Double? = null,
    val totalPrice: Double? = null,
    val eta: String? = null,
    val comparable: Boolean = true,
    val available: Boolean = true,
    val evidence: String? = null,
    val unavailableReason: String? = null
)

internal data class PhoneAgentScreen(
    val screenshot: GuiScreenshot,
    val application: String? = null,
    val displayWidth: Int = screenshot.width,
    val displayHeight: Int = screenshot.height
)

internal data class PhoneAgentDeviceResult(
    val success: Boolean,
    val detail: String? = null
)

internal data class PhoneAgentCommand(
    val action: String,
    val app: String? = null,
    val text: String? = null,
    val message: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val durationMs: Int? = null
) {
    fun fingerprint(): String = listOf(
        action,
        app,
        text,
        x,
        y,
        startX,
        startY,
        endX,
        endY
    ).joinToString("|")
}

internal sealed interface PhoneAgentDecision {
    data class Execute(val command: PhoneAgentCommand) : PhoneAgentDecision
    data class Finish(val message: String) : PhoneAgentDecision
    data class Invalid(val reason: String) : PhoneAgentDecision
}

internal interface PhoneAgentDevice {
    suspend fun observe(): PhoneAgentScreen
    suspend fun execute(command: PhoneAgentCommand, screen: PhoneAgentScreen): PhoneAgentDeviceResult
}
