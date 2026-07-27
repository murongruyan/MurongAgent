package com.murong.agent.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FoodDeliveryComparisonParserTest {
    @Test
    fun normalizesFeesAndFindsCheapestComparableOffer() {
        val comparison = FoodDeliveryComparisonParser.parse(
            """
            结果：
            {
              "query":"大杯拿铁",
              "offers":[
                {"platform":"美团","itemPrice":"¥20","packingFee":1,"deliveryFee":3,"discount":2,"comparable":true},
                {"platform":"饿了么","totalPrice":"19.50元","comparable":true},
                {"platform":"京东秒送","totalPrice":17,"comparable":false},
                {"platform":"淘宝闪购","available":false,"unavailableReason":"未安装"}
              ],
              "notes":["相同规格"]
            }
            """.trimIndent()
        )

        assertNotNull(comparison)
        assertEquals(4, comparison.offers.size)
        assertEquals(22.0, comparison.offers.first().totalPrice)
        assertEquals("饿了么", comparison.cheapestOffer?.platform)
        assertFalse(comparison.offers.last().available)
    }

    @Test
    fun rejectsUnstructuredOrEmptyResults() {
        assertNull(FoodDeliveryComparisonParser.parse("没有可靠结果"))
        assertNull(FoodDeliveryComparisonParser.parse("""{"offers":[]}"""))
    }
}
