package com.murong.agent.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSearchQueryPolicyTest {
    @Test
    fun `spoken search command becomes useful AI news keywords`() {
        assertEquals(
            "人工智能 最新大模型消息",
            prepareWebSearchKeywords("搜索一下最新大模型消息"),
        )
    }

    @Test
    fun `ordinary search keywords are preserved`() {
        assertEquals("北京天气", prepareWebSearchKeywords("查一下北京天气"))
    }

    @Test
    fun `transport errors are never treated as search evidence`() {
        assertFalse(isUsableWebSearchResult("网页搜索暂时不可用：Bing 超时"))
        assertFalse(isUsableWebSearchResult("联网检索超时：12 秒内没有返回结果。"))
        assertFalse(isUsableWebSearchResult("Error: 'query' parameter required"))
        assertTrue(isUsableWebSearchResult("搜索源: Bing 抓取\n\n1. AI 新闻\n   URL: https://example.com"))
    }
}
