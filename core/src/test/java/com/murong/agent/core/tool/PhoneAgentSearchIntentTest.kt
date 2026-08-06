package com.murong.agent.core.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentSearchIntentTest {
    @Test
    fun `parses search-only shopping request`() {
        val intent = PhoneAgentSearchIntent.parse("打开拼多多搜索oppo150w充电器")!!
        assertEquals("com.xunmeng.pinduoduo", intent.packageName)
        assertEquals("oppo150w充电器", intent.query)
        assertTrue(intent.searchOnly)
    }

    @Test
    fun `keeps follow-up action out of search query`() {
        val intent = PhoneAgentSearchIntent.parse("打开抖音搜索极客湾给它最新视频点赞")!!
        assertEquals("com.ss.android.ugc.aweme", intent.packageName)
        assertEquals("极客湾", intent.query)
        assertFalse(intent.searchOnly)
        assertEquals(
            "snssdk1128://search?keyword=%E6%9E%81%E5%AE%A2%E6%B9%BE",
            intent.verifiedNativeSearchUri(),
        )
    }

    @Test
    fun `does not infer search without an app before it`() {
        assertNull(PhoneAgentSearchIntent.parse("搜索附近的餐厅"))
    }

    @Test
    fun `finds generic top search entry from ocr`() {
        val entry = listOf(
            PhoneAgentTextElement("推荐", 80, 130),
            PhoneAgentTextElement("vivo1.5米数据线", 470, 52),
            PhoneAgentTextElement("多多买菜", 150, 280),
        ).likelySearchEntry()

        assertEquals("vivo1.5米数据线", entry?.text)
    }

    @Test
    fun `prefers explicit search label`() {
        val entry = listOf(
            PhoneAgentTextElement("商品", 420, 48),
            PhoneAgentTextElement("搜索", 850, 70),
        ).likelySearchEntry()

        assertEquals("搜索", entry?.text)
    }

    @Test
    fun `douyin search never falls back to navigation labels`() {
        val entry = listOf(
            PhoneAgentTextElement("房县", 390, 95),
            PhoneAgentTextElement("关注 1", 510, 95),
            PhoneAgentTextElement("推荐", 650, 95),
        ).likelySearchEntry(allowGenericTopBar = false)

        assertNull(entry)
    }

    @Test
    fun `normalizes noisy navigation labels in generic fallback`() {
        val entry = listOf(
            PhoneAgentTextElement("关注·1", 510, 95),
            PhoneAgentTextElement("朋友 |", 650, 95),
        ).likelySearchEntry()

        assertNull(entry)
    }
}
