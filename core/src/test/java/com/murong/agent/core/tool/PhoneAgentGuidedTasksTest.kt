package com.murong.agent.core.tool

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

class PhoneAgentGuidedTasksTest {
    @Test
    fun `meituan drink intent uses corrected Zhangjiagang Deji location`() {
        val intent = assertNotNull(
            MeituanDrinkTaskIntent.parse(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        assertEquals("江苏省张家港市德积镇", intent.locationQuery)
        assertEquals("蜜雪冰城", intent.storeQuery)
        assertEquals("冰鲜柠檬水", intent.itemName)
        assertNull(
            MeituanDrinkTaskIntent.parse(
                "打开美团把地址更改到江苏省得积镇的地址点一杯蜜雪冰城",
            ),
        )
    }

    @Test
    fun `meituan guidance changes location before opening takeaway`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val location = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("青峰镇", 150, 85),
                    PhoneAgentTextElement("外卖", 110, 230),
                ),
            ),
        )
        assertEquals("open_meituan_location_picker", location.traceAction)

        val focus = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("城市/区县/商场等地点", 420, 80),
                ),
            ),
        )
        assertEquals("focus_meituan_location_search", focus.traceAction)
        task.onActionResult(focus, true)
        val type = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(packageName = PhoneAgentApps.MEITUAN_PACKAGE),
            ),
        )
        assertEquals("江苏省张家港市德积镇", type.command.text)
        task.onActionResult(type, true)
        val waitResults = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(screen(packageName = PhoneAgentApps.MEITUAN_PACKAGE)),
        )
        assertEquals("wait_meituan_location_results", waitResults.traceAction)
        task.onActionResult(waitResults, true)
        val select = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("江苏省张家港市德积镇", 420, 80),
                    PhoneAgentTextElement("德积街道", 180, 160),
                    PhoneAgentTextElement("苏州市张家港市", 200, 200),
                ),
            ),
        )
        assertEquals("select_meituan_deji_location", select.traceAction)
        task.onActionResult(select, true)

        val directTakeaway = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("外卖", 110, 230),
                ),
            ),
        )
        assertEquals("open_meituan_takeaway", directTakeaway.traceAction)

        val closePicker = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("城市/区县/商场等地点", 420, 80),
                    PhoneAgentTextElement("当前选择：张家港·德积街道", 240, 185),
                    PhoneAgentTextElement("当前定位 青峰镇", 150, 260),
                ),
            ),
        )
        assertEquals("close_meituan_location_picker", closePicker.traceAction)
        assertEquals("Back", closePicker.command.action)

        val takeaway = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("张家港·德积街道", 180, 85),
                    PhoneAgentTextElement("外卖", 110, 230),
                ),
            ),
        )
        assertEquals("open_meituan_takeaway", takeaway.traceAction)
    }

    @Test
    fun `meituan guidance verifies cart item and never submits order`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val storeSearch = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 400, 220),
                    PhoneAgentTextElement("点菜", 120, 450),
                    PhoneAgentTextElement("评价723", 300, 450),
                    PhoneAgentTextElement("商家", 500, 450),
                ),
            ),
        )
        assertEquals("open_meituan_store_search", storeSearch.traceAction)
        assertEquals(455, storeSearch.command.x)
        assertEquals(72, storeSearch.command.y)
        task.onActionResult(storeSearch, true)

        val typeItem = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(screen(packageName = PhoneAgentApps.MEITUAN_PACKAGE)),
        )
        assertEquals("type_meituan_item_query", typeItem.traceAction)
        assertEquals("冰鲜柠檬水", typeItem.command.text)
        task.onActionResult(typeItem, true)

        val specification = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("冰鲜柠檬水", 550, 760),
                    PhoneAgentTextElement("选规格", 900, 770),
                ),
            ),
        )
        assertEquals("open_meituan_item_specification", specification.traceAction)

        val add = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("冰鲜柠檬水", 500, 250),
                    PhoneAgentTextElement("规格", 100, 330),
                    PhoneAgentTextElement("温度", 100, 430),
                    PhoneAgentTextElement("糖度", 100, 560),
                ),
            ),
        )
        assertEquals("add_meituan_item_to_cart", add.traceAction)
        assertEquals(912, add.command.x)
        assertEquals(810, add.command.y)
        task.onActionResult(add, true)

        val closeSpecification = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("冰鲜柠檬水", 500, 250),
                    PhoneAgentTextElement("规格", 100, 330),
                    PhoneAgentTextElement("温度", 100, 430),
                    PhoneAgentTextElement("糖度", 100, 560),
                ),
            ),
        )
        assertEquals("close_meituan_item_specification", closeSpecification.traceAction)

        val details = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("明细", 390, 930),
                    PhoneAgentTextElement("去结算", 850, 930),
                ),
            ),
        )
        assertEquals("open_meituan_cart_details", details.traceAction)
        task.onActionResult(details, true)

        val finish = assertIs<PhoneAgentGuidedDecision.Finish>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("已加购商品", 150, 650),
                    PhoneAgentTextElement("冰鲜柠檬水", 420, 760),
                    PhoneAgentTextElement("去结算", 850, 930),
                ),
            ),
        )
        assert(finish.message.contains("未支付"))
    }

    @Test
    fun `meituan never treats search result text as verified cart contents`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val details = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("明细", 390, 930),
                ),
            ),
        )
        task.onActionResult(details, true)

        val retry = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("冰鲜柠檬水", 380, 125),
                    PhoneAgentTextElement("选规格", 900, 215),
                ),
            ),
        )
        assertEquals("retry_meituan_cart_details", retry.traceAction)
        assertEquals(55, retry.command.x)
        assertEquals(950, retry.command.y)
    }

    @Test
    fun `meituan store search never falls back to blind category scrolling`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val search = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 400, 220),
                    PhoneAgentTextElement("点菜", 120, 450),
                    PhoneAgentTextElement("评价723", 300, 450),
                    PhoneAgentTextElement("商家", 500, 450),
                    PhoneAgentTextElement("已经到底", 500, 700),
                ),
            ),
        )
        assertEquals("open_meituan_store_search", search.traceAction)
        assertNotEquals("scroll_meituan_fruit_tea", search.traceAction)
    }

    @Test
    fun `meituan recovers from a retained unrelated store search history page`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val recover = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("请输入商品名", 420, 70),
                    PhoneAgentTextElement("热门搜索", 100, 150),
                    PhoneAgentTextElement("历史搜索", 100, 280),
                    PhoneAgentTextElement("冰鲜柠檬水", 160, 330),
                ),
            ),
        )

        assertEquals("recover_meituan_from_store_search_history", recover.traceAction)
        assertEquals("Back", recover.command.action)
    }

    @Test
    fun `meituan reuses verified matching cart item instead of adding a duplicate`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val details = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 400, 220),
                    PhoneAgentTextElement("点菜", 120, 450),
                    PhoneAgentTextElement("评价723", 300, 450),
                    PhoneAgentTextElement("商家", 500, 450),
                    PhoneAgentTextElement("明细", 390, 930),
                ),
            ),
        )
        assertEquals("open_meituan_cart_details", details.traceAction)
        task.onActionResult(details, true)

        val finish = assertIs<PhoneAgentGuidedDecision.Finish>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("已加购商品", 150, 650),
                    PhoneAgentTextElement("冰鲜柠檬水", 280, 760),
                ),
            ),
        )
        assert(finish.message.contains("未支付"))
    }

    @Test
    fun `meituan minimum order shortfall requests user choice before adding more`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val details = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("明细", 390, 930),
                ),
            ),
        )
        task.onActionResult(details, true)

        val choice = assertIs<PhoneAgentGuidedDecision.NeedsUserAction>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("已加购商品", 150, 650),
                    PhoneAgentTextElement("冰鲜柠檬水", 280, 760),
                    PhoneAgentTextElement("再买¥11.2，可以起送（凑单）", 380, 610),
                ),
            ),
        )
        assert(choice.message.contains("再加同款"))
        assert(choice.message.contains("推荐好喝"))
        assert(choice.message.contains("尚未提交订单或支付"))
    }

    @Test
    fun `meituan coupon threshold is optional user choice not minimum order failure`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val details = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("明细", 390, 930),
                ),
            ),
        )
        assertEquals(55, details.command.x)
        task.onActionResult(details, true)

        val choice = assertIs<PhoneAgentGuidedDecision.NeedsUserAction>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("已加购商品", 150, 650),
                    PhoneAgentTextElement("冰鲜柠檬水", 280, 760),
                    PhoneAgentTextElement("再买¥7.6，可减¥13（凑单）", 380, 610),
                    PhoneAgentTextElement("去结算", 850, 930),
                ),
            ),
        )
        assert(choice.message.contains("已经可以结算"))
        assert(choice.message.contains("优惠券凑单机会"))
        assert(choice.message.contains("不会支付"))
    }

    @Test
    fun `meituan follow up can add same item from retained cart`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城\n" +
                    "外卖续接选择：再加一杯同款",
            ),
        )
        val add = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("已加购商品", 150, 650),
                    PhoneAgentTextElement("冰鲜柠檬水", 280, 760),
                    PhoneAgentTextElement("差¥11.2起送", 380, 610),
                ),
            ),
        )
        assertEquals("add_meituan_same_item_from_cart", add.traceAction)
        assertEquals(955, add.command.x)
        assertEquals(760, add.command.y)
    }

    @Test
    fun `meituan follow up recommendation closes cart before selecting another drink`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城\n" +
                    "外卖续接选择：你自己看哪些好喝，点那个",
            ),
        )
        val close = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("已加购商品", 150, 650),
                    PhoneAgentTextElement("冰鲜柠檬水", 280, 760),
                    PhoneAgentTextElement("再买¥7.6，可减¥13（凑单）", 380, 610),
                ),
            ),
        )
        assertEquals("close_meituan_cart_for_recommendation", close.traceAction)
        assertEquals(955, close.command.x)
        assertEquals(455, close.command.y)
        task.onActionResult(close, true)

        val recommend = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("柠檬绿茶", 350, 300),
                    PhoneAgentTextElement("月售1000+ 20+回头客推荐", 400, 335),
                    PhoneAgentTextElement("选规格", 920, 355),
                ),
            ),
        )
        assertEquals("open_meituan_recommended_specification", recommend.traceAction)
        assertEquals(920, recommend.command.x)
        assertEquals(355, recommend.command.y)
    }

    @Test
    fun `meituan OCR miss uses bounded first exact result fallback without scrolling`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val search = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 400, 220),
                    PhoneAgentTextElement("点菜", 120, 450),
                    PhoneAgentTextElement("评价723", 300, 450),
                    PhoneAgentTextElement("商家", 500, 450),
                ),
            ),
        )
        task.onActionResult(search, true)
        val type = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(screen(packageName = PhoneAgentApps.MEITUAN_PACKAGE)),
        )
        task.onActionResult(type, true)
        repeat(2) {
            val wait = assertIs<PhoneAgentGuidedDecision.Execute>(
                task.next(screen(packageName = PhoneAgentApps.MEITUAN_PACKAGE)),
            )
            assertEquals("wait_meituan_item_search_results", wait.traceAction)
            task.onActionResult(wait, true)
        }
        val fallback = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(screen(packageName = PhoneAgentApps.MEITUAN_PACKAGE)),
        )
        assertEquals("open_meituan_first_item_spec_fallback", fallback.traceAction)
        assertEquals(920, fallback.command.x)
        assertEquals(215, fallback.command.y)
        assertNotEquals("scroll_meituan_fruit_tea", fallback.traceAction)
    }

    @Test
    fun `meituan product row uses row aligned spec fallback when button OCR is missing`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val search = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 400, 220),
                    PhoneAgentTextElement("点菜", 120, 450),
                    PhoneAgentTextElement("评价723", 300, 450),
                    PhoneAgentTextElement("商家", 500, 450),
                ),
            ),
        )
        task.onActionResult(search, true)
        val type = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(screen(packageName = PhoneAgentApps.MEITUAN_PACKAGE)),
        )
        task.onActionResult(type, true)

        val fallback = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("冰鲜柠檬水", 430, 70),
                    PhoneAgentTextElement("冰鲜柠檬水", 380, 125),
                ),
            ),
        )
        assertEquals("open_meituan_item_specification_text_fallback", fallback.traceAction)
        assertEquals(920, fallback.command.x)
        assertEquals(215, fallback.command.y)
    }

    @Test
    fun `meituan waits for verified store page before tapping in store search`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val storeResult = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 420, 260),
                ),
            ),
        )
        assertEquals("open_meituan_mixue_global_results", storeResult.traceAction)
        task.onActionResult(storeResult, true)

        val actualStore = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("综合排序", 110, 300),
                    PhoneAgentTextElement("速度优先", 420, 300),
                    PhoneAgentTextElement("销量优先", 750, 300),
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 360, 390),
                ),
            ),
        )
        assertEquals("open_meituan_mixue_store", actualStore.traceAction)
        task.onActionResult(actualStore, true)

        val wait = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 300, 80),
                ),
            ),
        )
        assertEquals("wait_meituan_store_page", wait.traceAction)
        assertNotEquals("open_meituan_store_search", wait.traceAction)
        task.onActionResult(wait, true)

        val search = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 400, 220),
                    PhoneAgentTextElement("点菜", 120, 450),
                    PhoneAgentTextElement("评价723", 300, 450),
                    PhoneAgentTextElement("商家", 500, 450),
                ),
            ),
        )
        assertEquals("open_meituan_store_search", search.traceAction)
    }

    @Test
    fun `meituan does not keep reopening the same store suggestion while results render`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val suggestion = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 420, 260),
                ),
            ),
        )
        assertEquals("open_meituan_mixue_global_results", suggestion.traceAction)
        task.onActionResult(suggestion, true)

        val merchant = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 360, 390),
                ),
            ),
        )
        assertEquals("open_meituan_mixue_store", merchant.traceAction)
        task.onActionResult(merchant, true)

        val wait = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("蜜雪冰城(德积店)", 300, 80),
                ),
            ),
        )
        assertEquals("wait_meituan_store_page", wait.traceAction)
    }

    @Test
    fun `meituan requests a deliverable address when the selected town has no merchants`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        val decision = assertIs<PhoneAgentGuidedDecision.NeedsUserAction>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.MEITUAN_PACKAGE,
                    PhoneAgentTextElement("当前定位下无商家，请切换地址试试~", 500, 650),
                ),
            ),
        )
        assert(decision.message.contains("具体小区、道路门牌"))
        assert(decision.message.contains("尚未添加商品、提交订单或支付"))
    }

    @Test
    fun `meituan unknown frames hand current screenshot back to model recovery`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create(
                "打开美团把地址更改到江苏省张家港市德积镇的地址点一杯蜜雪冰城",
            ),
        )
        repeat(6) {
            val wait = assertIs<PhoneAgentGuidedDecision.Execute>(
                task.next(screen(packageName = PhoneAgentApps.MEITUAN_PACKAGE)),
            )
            assertEquals("wait_meituan_known_flow_redraw", wait.traceAction)
            task.onActionResult(wait, true)
        }
        val recovery = assertIs<PhoneAgentGuidedDecision.RecoverWithModel>(
            task.next(screen(packageName = PhoneAgentApps.MEITUAN_PACKAGE)),
        )
        assert(recovery.reason.contains("自行判断并恢复路径"))
        assert(recovery.reason.contains("不要提交订单或支付"))
    }

    @Test
    fun `douyin like guidance is disabled until target search is verified`() {
        assertNull(
            PhoneAgentGuidedTask.create(
                "打开抖音搜索极客湾给它最新视频点赞",
                searchContextVerified = false,
            ),
        )
    }

    @Test
    fun `video call intent accepts full and shortened Chinese wording`() {
        assertEquals(
            PhoneAgentVideoCallIntent("com.tencent.mm", "慕容茹艳"),
            PhoneAgentVideoCallIntent.parse("打开微信给慕容茹艳打视频通话"),
        )
        assertEquals(
            PhoneAgentVideoCallIntent(PhoneAgentApps.DOUYIN_PACKAGE, "慕容茹艳"),
            PhoneAgentVideoCallIntent.parse("打开抖音给慕容茹艳打视通话"),
        )
    }

    @Test
    fun `douyin result skill opens verified account and never follow or unrelated text`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开抖音搜索极客湾给它最新视频点赞"),
        )
        val decision = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("极客湾Geekerwan", 310, 190),
                    PhoneAgentTextElement("关注", 850, 210),
                    PhoneAgentTextElement("极客湾手机续航大横评", 280, 610),
                ),
            ),
        )
        assertEquals("open_query_profile", decision.traceAction)
        assertEquals(310, decision.command.x)
        assertEquals(190, decision.command.y)
        assertNotEquals(850, decision.command.x)
    }

    @Test
    fun `douyin comprehensive search switches to user tab before model`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开抖音搜索极客湾给它最新视频点赞"),
        )
        val decision = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("综合", 90, 120),
                    PhoneAgentTextElement("视频", 220, 120),
                    PhoneAgentTextElement("用户", 360, 120),
                    PhoneAgentTextElement("别ban我喔", 220, 230),
                    PhoneAgentTextElement("极客湾也太坏了吧", 320, 300),
                ),
            ),
        )
        assertEquals("open_douyin_users_tab", decision.traceAction)
        assertEquals(360, decision.command.x)
        assertEquals(120, decision.command.y)
        task.onActionResult(decision, true)

        val wait = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("用户", 360, 120),
                    PhoneAgentTextElement("极客湾Geekerwan", 250, 190),
                ),
            ),
        )
        assertEquals("wait_douyin_users_tab", wait.traceAction)
        task.onActionResult(wait, true)

        val account = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("用户", 360, 120),
                    PhoneAgentTextElement("极客湾Geekerwan", 250, 190),
                ),
            ),
        )
        assertEquals("open_query_profile", account.traceAction)
        assertEquals(250, account.command.x)
        assertEquals(190, account.command.y)
    }

    @Test
    fun `douyin does not blind tap when user results have not exposed target account`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开抖音搜索极客湾给它最新视频点赞"),
        )
        val switch = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("综合", 90, 120),
                    PhoneAgentTextElement("用户", 360, 120),
                ),
            ),
        )
        task.onActionResult(switch, true)
        val wait = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(screen(packageName = PhoneAgentApps.DOUYIN_PACKAGE)),
        )
        assertEquals("wait_douyin_users_tab", wait.traceAction)
        task.onActionResult(wait, true)

        assertNull(task.next(screen(packageName = PhoneAgentApps.DOUYIN_PACKAGE)))
    }

    @Test
    fun `douyin switches tabs when OCR merges the whole tab row`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开抖音搜索极客湾给它最新视频点赞"),
        )
        val decision = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("综合 视频 用户 图文 团购", 405, 130),
                    PhoneAgentTextElement("极客湾Geekerwan", 250, 200),
                ),
            ),
        )
        assertEquals("open_douyin_users_tab", decision.traceAction)
        assertEquals(405, decision.command.x)
        assertEquals(130, decision.command.y)
    }

    @Test
    fun `douyin video uses idempotent video double tap when count OCR is unavailable`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开抖音搜索极客湾给它最新视频点赞"),
        )
        val decision = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("@极客湾Geekerwan", 260, 820),
                    PhoneAgentTextElement("极客湾手机续航大横评 展开", 310, 870),
                    PhoneAgentTextElement("21.3万", 925, 630),
                ),
            ),
        )
        assertEquals("like_latest_video", decision.traceAction)
        assertEquals("Double Tap", decision.command.action)
        assertEquals(500, decision.command.x)
        assertEquals(450, decision.command.y)
    }

    @Test
    fun `douyin profile bio at-sign is not mistaken for a video`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开抖音搜索极客湾给它最新视频点赞"),
        )
        val decision = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("极客湾Geekerwan", 350, 180),
                    PhoneAgentTextElement("@极客湾 商务合作", 320, 350),
                    PhoneAgentTextElement("作品 131", 170, 500),
                    PhoneAgentTextElement("置顶", 60, 545),
                ),
            ),
        )
        assertEquals("open_latest_non_pinned_video", decision.traceAction)
        assertEquals(500, decision.command.x)
    }

    @Test
    fun `douyin profile skips only pinned tile and keeps latest row`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开抖音搜索极客湾给它最新视频点赞"),
        )
        val decision = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("极客湾Geekerwan", 350, 180),
                    PhoneAgentTextElement("作品 131", 170, 500),
                    PhoneAgentTextElement("置顶", 60, 545),
                ),
            ),
        )
        assertEquals("open_latest_non_pinned_video", decision.traceAction)
        assertEquals(500, decision.command.x)
        assertEquals(625, decision.command.y)
    }

    @Test
    fun `douyin profile uses visual pinned fallback when OCR misses badge text`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开抖音搜索极客湾给它最新视频点赞"),
        )
        val decision = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("极客湾Geekerwan", 350, 180),
                    PhoneAgentTextElement("作品 131", 170, 500),
                ),
            ),
        )
        assertEquals("open_latest_non_pinned_video", decision.traceAction)
        assertEquals(500, decision.command.x)
        assertEquals(625, decision.command.y)
    }

    @Test
    fun `douyin waits for profile grid before inspecting pinned tiles`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开抖音搜索极客湾给它最新视频点赞"),
        )
        val account = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("极客湾Geekerwan", 310, 190),
                    PhoneAgentTextElement("关注", 850, 210),
                ),
            ),
        )
        task.onActionResult(account, true)

        val wait = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("极客湾Geekerwan", 350, 180),
                    PhoneAgentTextElement("作品 131", 170, 500),
                ),
            ),
        )
        assertEquals("wait_query_profile", wait.traceAction)
        task.onActionResult(wait, true)

        val video = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("极客湾Geekerwan", 350, 180),
                    PhoneAgentTextElement("作品 131", 170, 500),
                    PhoneAgentTextElement("置顶", 60, 545),
                ),
            ),
        )
        assertEquals("open_latest_non_pinned_video", video.traceAction)
        assertEquals(500, video.command.x)
    }

    @Test
    fun `guided call focuses search then types dynamic recipient`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开抖音给慕容茹艳打视频通话"),
        )
        val searchScreen = screen(
            packageName = PhoneAgentApps.DOUYIN_PACKAGE,
            PhoneAgentTextElement("搜索联系人、群聊或聊天记录", 420, 90),
            PhoneAgentTextElement("取消", 930, 90),
        )
        val focus = assertIs<PhoneAgentGuidedDecision.Execute>(task.next(searchScreen))
        assertEquals("focus_call_search", focus.traceAction)
        task.onActionResult(focus, true)

        val type = assertIs<PhoneAgentGuidedDecision.Execute>(task.next(searchScreen))
        assertEquals("type_call_recipient", type.traceAction)
        assertEquals("慕容茹艳", type.command.text)
    }

    @Test
    fun `wechat call types after verified home search even when new search page has no text`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开微信给慕容茹艳打视频通话"),
        )
        val openSearch = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = "com.tencent.mm",
                    PhoneAgentTextElement("微信", 500, 70),
                    PhoneAgentTextElement("通讯录", 300, 940),
                    PhoneAgentTextElement("发现", 600, 940),
                    PhoneAgentTextElement("我", 850, 940),
                ),
            ),
        )
        assertEquals("open_wechat_search", openSearch.traceAction)
        task.onActionResult(openSearch, true)

        val type = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(screen(packageName = "com.tencent.mm")),
        )
        assertEquals("type_call_recipient", type.traceAction)
        assertEquals("慕容茹艳", type.command.text)
    }

    @Test
    fun `wechat call can resume from the new local or web search surface`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开微信给慕容茹艳打视频通话"),
        )

        val type = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = "com.tencent.mm",
                    PhoneAgentTextElement("搜索本地或网络结果", 420, 90),
                    PhoneAgentTextElement("AI搜索", 900, 180),
                ),
            ),
        )

        assertEquals("type_call_recipient", type.traceAction)
        assertEquals("慕容茹艳", type.command.text)
    }

    @Test
    fun `wechat call can resume from FTS activity when semantics and OCR are empty`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开微信给慕容茹艳打视频通话"),
        )

        val focus = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = "com.tencent.mm",
                    windowClassName = "com.tencent.mm.plugin.fts.ui.FTSMainUI",
                ),
            ),
        )

        assertEquals("focus_call_search", focus.traceAction)
        assertEquals(500, focus.command.x)
        assertEquals(85, focus.command.y)
        task.onActionResult(focus, true)

        val type = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = "com.tencent.mm",
                    windowClassName = "com.tencent.mm.plugin.fts.ui.FTSMainUI",
                ),
            ),
        )
        assertEquals("type_call_recipient", type.traceAction)
        assertEquals("慕容茹艳", type.command.text)
        assertEquals(500, type.command.x)
        assertEquals(85, type.command.y)
    }

    @Test
    fun `douyin call types after verified message search without legacy placeholder`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开抖音给慕容茹艳打视频通话"),
        )
        val openSearch = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = PhoneAgentApps.DOUYIN_PACKAGE,
                    PhoneAgentTextElement("消息", 500, 80),
                ),
            ),
        )
        assertEquals("open_douyin_message_search", openSearch.traceAction)
        task.onActionResult(openSearch, true)

        val type = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(screen(packageName = PhoneAgentApps.DOUYIN_PACKAGE)),
        )
        assertEquals("type_call_recipient", type.traceAction)
        assertEquals("慕容茹艳", type.command.text)
    }

    @Test
    fun `wechat call selects only an exact contact row and ignores lower message hits`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开微信给慕容茹艳打视频通话"),
        )
        val decision = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = "com.tencent.mm",
                    PhoneAgentTextElement("联系人", 90, 190),
                    PhoneAgentTextElement("慕容茹艳", 260, 280),
                    PhoneAgentTextElement("群聊", 90, 390),
                    PhoneAgentTextElement("包含：慕容茹艳", 300, 470),
                    PhoneAgentTextElement("聊天记录", 110, 560),
                    PhoneAgentTextElement("ai代充的子辰007", 280, 650),
                    PhoneAgentTextElement("慕容茹艳", 300, 700),
                    windowClassName = "com.tencent.mm.plugin.fts.ui.FTSMainUI",
                ),
            ),
        )
        assertEquals("open_call_recipient", decision.traceAction)
        assertEquals(260, decision.command.x)
        assertEquals(280, decision.command.y)
    }

    @Test
    fun `wechat call asks for disambiguation when exact contact names are duplicated`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开微信给慕容茹艳打视频通话"),
        )
        val decision = assertIs<PhoneAgentGuidedDecision.NeedsUserAction>(
            task.next(
                screen(
                    packageName = "com.tencent.mm",
                    PhoneAgentTextElement("联系人", 90, 190),
                    PhoneAgentTextElement("慕容茹艳", 260, 280),
                    PhoneAgentTextElement("慕容茹艳", 260, 360),
                    PhoneAgentTextElement("群聊", 90, 450),
                    windowClassName = "com.tencent.mm.plugin.fts.ui.FTSMainUI",
                ),
            ),
        )
        assert(decision.message.contains("2个"))
        assert(decision.message.contains("仅凭姓名无法安全确定"))
    }

    @Test
    fun `wechat call never confirms a video choice before recipient chat verification`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开微信给慕容茹艳打视频通话"),
        )
        val decision = task.next(
            screen(
                packageName = "com.tencent.mm",
                PhoneAgentTextElement("视频通话", 500, 820),
                PhoneAgentTextElement("语音通话", 500, 885),
            ),
        )
        assertNull(decision)
    }

    @Test
    fun `wechat call never treats a chat record message hit as a contact`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开微信给慕容茹艳打视频通话"),
        )
        val search = screen(
            packageName = "com.tencent.mm",
            PhoneAgentTextElement("搜索本地或网络结果", 420, 90),
            windowClassName = "com.tencent.mm.plugin.fts.ui.FTSMainUI",
        )
        val focus = assertIs<PhoneAgentGuidedDecision.Execute>(task.next(search))
        assertEquals("focus_call_search", focus.traceAction)
        task.onActionResult(focus, true)
        val type = assertIs<PhoneAgentGuidedDecision.Execute>(task.next(search))
        assertEquals("type_call_recipient", type.traceAction)
        task.onActionResult(type, true)
        val wait = assertIs<PhoneAgentGuidedDecision.Execute>(task.next(search))
        assertEquals("wait_call_search_results", wait.traceAction)
        task.onActionResult(wait, true)

        val recovery = assertIs<PhoneAgentGuidedDecision.RecoverWithModel>(
            task.next(
                screen(
                    packageName = "com.tencent.mm",
                    PhoneAgentTextElement("聊天记录", 110, 300),
                    PhoneAgentTextElement("ai代充的子辰007", 280, 390),
                    PhoneAgentTextElement("慕容茹艳", 300, 450),
                    windowClassName = "com.tencent.mm.plugin.fts.ui.FTSMainUI",
                ),
            ),
        )
        assert(recovery.reason.contains("不要把消息正文当成联系人"))
    }

    @Test
    fun `wechat call backs out when opened chat header is not the recipient`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开微信给慕容茹艳打视频通话"),
        )
        val open = assertIs<PhoneAgentGuidedDecision.Execute>(
            task.next(
                screen(
                    packageName = "com.tencent.mm",
                    PhoneAgentTextElement("联系人", 90, 190),
                    PhoneAgentTextElement("慕容茹艳", 260, 280),
                    PhoneAgentTextElement("聊天记录", 110, 390),
                    windowClassName = "com.tencent.mm.plugin.fts.ui.FTSMainUI",
                ),
            ),
        )
        task.onActionResult(open, true)

        val wrongChat = screen(
            packageName = "com.tencent.mm",
            PhoneAgentTextElement("ai代充的子辰007", 500, 80),
            PhoneAgentTextElement("慕容茹艳", 500, 420),
            PhoneAgentTextElement("发送", 900, 910),
            windowClassName = "com.tencent.mm.ui.LauncherUI",
        )
        val wait = assertIs<PhoneAgentGuidedDecision.Execute>(task.next(wrongChat))
        assertEquals("wait_call_recipient_chat", wait.traceAction)
        task.onActionResult(wait, true)

        val back = assertIs<PhoneAgentGuidedDecision.Execute>(task.next(wrongChat))
        assertEquals("back_from_wrong_call_recipient", back.traceAction)
        assertEquals("Back", back.command.action)
        assertNotEquals("open_wechat_more_actions", back.traceAction)
    }

    @Test
    fun `guided call completes only after video call choice was accepted`() {
        val task = assertNotNull(
            PhoneAgentGuidedTask.create("打开微信给慕容茹艳打视频通话"),
        )
        val chatScreen = screen(
            packageName = "com.tencent.mm",
            PhoneAgentTextElement("慕容茹艳", 500, 80),
            PhoneAgentTextElement("发送", 900, 910),
            windowClassName = "com.tencent.mm.ui.LauncherUI",
        )
        val menu = assertIs<PhoneAgentGuidedDecision.Execute>(task.next(chatScreen))
        assertEquals("open_wechat_more_actions", menu.traceAction)
        task.onActionResult(menu, true)

        val choiceScreen = screen(
            packageName = "com.tencent.mm",
            PhoneAgentTextElement("视频通话", 500, 820),
            PhoneAgentTextElement("语音通话", 500, 885),
            PhoneAgentTextElement("取消", 500, 950),
        )
        val choice = assertIs<PhoneAgentGuidedDecision.Execute>(task.next(choiceScreen))
        assertEquals("confirm_video_call", choice.traceAction)
        task.onActionResult(choice, true)

        val finished = assertIs<PhoneAgentGuidedDecision.Finish>(task.next(choiceScreen))
        assertEquals("已在微信向慕容茹艳发起视频通话", finished.message)
    }

    private fun screen(
        packageName: String,
        vararg elements: PhoneAgentTextElement,
        windowClassName: String? = null,
    ): PhoneAgentScreen = PhoneAgentScreen(
        screenshot = GuiScreenshot(
            mimeType = "image/png",
            base64Data = "",
            width = 1_000,
            height = 1_000,
        ),
        application = packageName,
        windowClassName = windowClassName,
        displayWidth = 1_000,
        displayHeight = 1_000,
        textElements = elements.toList(),
    )
}
