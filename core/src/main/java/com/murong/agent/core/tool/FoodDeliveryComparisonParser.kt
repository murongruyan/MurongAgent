package com.murong.agent.core.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

internal object FoodDeliveryComparisonParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(message: String): FoodDeliveryComparison? {
        val root = extractJsonObject(message) ?: return null
        val offerElements = root.array("offers", "报价", "results", "platforms") ?: return null
        val offers = offerElements.mapNotNull(::parseOffer)
        if (offers.isEmpty()) return null
        val cheapest = offers
            .asSequence()
            .filter { it.available && it.comparable && it.totalPrice != null }
            .minByOrNull { it.totalPrice!! }
        return FoodDeliveryComparison(
            query = root.string("query", "商品", "keyword", "目标"),
            offers = offers,
            cheapestOffer = cheapest,
            notes = root.stringList("notes", "备注", "warnings")
        )
    }

    private fun parseOffer(element: JsonElement): FoodDeliveryOffer? {
        val obj = element as? JsonObject ?: return null
        val platform = obj.string("platform", "平台", "app") ?: return null
        val quantity = obj.int("quantity", "数量")?.coerceAtLeast(1) ?: 1
        val itemPrice = obj.money("itemPrice", "item_price", "商品价", "商品金额")
        val packingFee = obj.money("packingFee", "packing_fee", "包装费")
        val deliveryFee = obj.money("deliveryFee", "delivery_fee", "配送费")
        val discount = obj.money("discount", "优惠", "优惠金额")
        val explicitTotal = obj.money(
            "totalPrice",
            "total_price",
            "finalPrice",
            "final_price",
            "到手价",
            "实付"
        )
        val calculatedTotal = if (itemPrice != null) {
            itemPrice + (packingFee ?: 0.0) + (deliveryFee ?: 0.0) - (discount ?: 0.0)
        } else {
            null
        }
        val unavailableReason = obj.string(
            "unavailableReason",
            "unavailable_reason",
            "不可用原因"
        )
        val available = obj.boolean("available", "可用")
            ?: unavailableReason.isNullOrBlank()
        return FoodDeliveryOffer(
            platform = platform,
            merchant = obj.string("merchant", "商家", "store"),
            item = obj.string("item", "商品", "name"),
            specification = obj.string("specification", "规格", "spec"),
            quantity = quantity,
            itemPrice = itemPrice,
            packingFee = packingFee,
            deliveryFee = deliveryFee,
            discount = discount,
            totalPrice = explicitTotal ?: calculatedTotal?.coerceAtLeast(0.0),
            eta = obj.string("eta", "送达时间", "deliveryTime"),
            comparable = obj.boolean("comparable", "可比") ?: true,
            available = available,
            evidence = obj.string("evidence", "证据", "priceEvidence"),
            unavailableReason = unavailableReason
        )
    }

    private fun extractJsonObject(message: String): JsonObject? {
        val direct = runCatching { json.parseToJsonElement(message.trim()) as? JsonObject }
            .getOrNull()
        if (direct != null) return direct
        val start = message.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until message.length) {
            val char = message[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\' && quote != null) {
                escaped = true
                continue
            }
            if (char == '"') {
                quote = if (quote == '"') null else '"'
                continue
            }
            if (quote != null) continue
            if (char == '{') depth++
            if (char == '}' && --depth == 0) {
                return runCatching {
                    json.parseToJsonElement(message.substring(start, index + 1)) as? JsonObject
                }.getOrNull()
            }
        }
        return null
    }

    private fun JsonObject.value(vararg keys: String): JsonElement? =
        keys.firstNotNullOfOrNull { this[it] }

    private fun JsonObject.string(vararg keys: String): String? =
        (value(*keys) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    private fun JsonObject.int(vararg keys: String): Int? {
        val primitive = value(*keys) as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.filter(Char::isDigit)?.toIntOrNull()
    }

    private fun JsonObject.boolean(vararg keys: String): Boolean? {
        val primitive = value(*keys) as? JsonPrimitive ?: return null
        return primitive.booleanOrNull ?: when (primitive.contentOrNull?.trim()?.lowercase()) {
            "是", "yes", "available", "可用" -> true
            "否", "no", "unavailable", "不可用" -> false
            else -> null
        }
    }

    private fun JsonObject.money(vararg keys: String): Double? {
        val primitive = value(*keys) as? JsonPrimitive ?: return null
        primitive.doubleOrNull?.let { return it }
        return MONEY_REGEX.find(primitive.contentOrNull.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
    }

    private fun JsonObject.array(vararg keys: String): JsonArray? =
        value(*keys) as? JsonArray

    private fun JsonObject.stringList(vararg keys: String): List<String> {
        return when (val value = value(*keys)) {
            is JsonArray -> value.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
            }
            is JsonPrimitive -> listOfNotNull(value.contentOrNull?.trim()?.takeIf(String::isNotBlank))
            else -> emptyList()
        }
    }

    private val MONEY_REGEX = Regex("""-?(\d+(?:\.\d+)?)""")
}
