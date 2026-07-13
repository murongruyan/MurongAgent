package com.murong.agent.core.retrieval

import kotlin.math.ln
import kotlin.math.min

/**
 * BM25 全文检索工具
 *
 * 提供一个轻量级、无外部依赖的 BM25 排序算法实现，支持 CJK（中日韩）分词和片段提取。
 * 适用于代码搜索、记忆检索、历史查询等场景。
 *
 * 移植自 Reasonix (DeepSeek-Reasonix) internal/retrieval/bm25.go
 */
object Bm25Retrieval {

    /**
     * 对文本进行分词：拉丁词转小写，CJK 文字拆分为单字。
     */
    fun tokens(s: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(buf.toString())
                buf.clear()
            }
        }
        for (ch in s) {
            when {
                ch.isCJK() -> {
                    flush()
                    out.add(ch.toString())
                }
                ch.isLetter() || ch.isDigit() || ch == '_' -> {
                    buf.append(ch.lowercaseChar())
                }
                else -> {
                    flush()
                }
            }
        }
        flush()
        return out
    }

    /**
     * 去重，保持首次出现顺序。
     */
    fun unique(terms: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return terms.filter { it.isNotEmpty() && it !in seen }.also {
            seen.addAll(it)
        }
    }

    /**
     * 计算词频映射。
     */
    fun counts(terms: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        for (term in terms) {
            map[term] = (map[term] ?: 0) + 1
        }
        return map
    }

    /**
     * 计算 BM25 得分。
     *
     * @param tf 文档的词频映射
     * @param docLength 文档长度（词数）
     * @param queryTerms 查询词
     * @param df 文档频率映射（词 -> 包含该词的文档数）
     * @param totalDocs 总文档数
     * @param avgDocLength 平均文档长度
     */
    fun bm25Score(
        tf: Map<String, Int>,
        docLength: Int,
        queryTerms: List<String>,
        df: Map<String, Int>,
        totalDocs: Int,
        avgDocLength: Double
    ): Double {
        val k1 = 1.2
        val b = 0.75
        if (docLength <= 0 || totalDocs <= 0) return 0.0
        val avgLen = if (avgDocLength <= 0) 1.0 else avgDocLength
        val docLen = docLength.toDouble()
        var score = 0.0
        for (term in queryTerms) {
            val termFreq = tf[term] ?: continue
            val termDf = df[term] ?: continue
            if (termDf == 0) continue
            val idf = ln(1.0 + (totalDocs - termDf + 0.5) / (termDf + 0.5))
            score += idf * (termFreq * (k1 + 1)) / (termFreq + k1 * (1 - b + b * docLen / avgLen))
        }
        return score
    }

    /**
     * 计算文档频率：每个词出现在多少篇文档中。
     */
    fun documentFrequency(docs: List<Map<String, Int>>): Map<String, Int> {
        val df = mutableMapOf<String, Int>()
        for (countMap in docs) {
            for (term in countMap.keys) {
                df[term] = (df[term] ?: 0) + 1
            }
        }
        return df
    }

    /**
     * 保留最高分及其相对比例以上的条目。输入必须已按降序排列。
     */
    fun <T> keepTopRelativeScore(
        items: List<T>,
        ratio: Double,
        score: (T) -> Double
    ): List<T> {
        if (items.isEmpty() || ratio <= 0) return items
        val top = score(items[0])
        if (top <= 0) return items
        val cutoff = top * ratio
        val result = mutableListOf<T>()
        for ((i, item) in items.withIndex()) {
            if (i == 0 || score(item) >= cutoff) {
                result.add(item)
            }
        }
        return result
    }

    /**
     * 规范化查询字符串，返回唯一词列表。如果无有效词则返回 null。
     */
    fun queryTerms(query: String): List<String>? {
        val terms = unique(tokens(query.trim()))
        return terms.ifEmpty { null }
    }

    /**
     * 生成围绕查询的摘要片段。
     */
    fun makeSnippet(
        text: String,
        query: String,
        terms: List<String>,
        maxChars: Int
    ): String {
        val compacted = compactWhitespace(text)
        if (maxChars <= 0 || compacted.length <= maxChars) return compacted
        val lower = compacted.lowercase()
        val queryTrimmed = query.trim().lowercase()
        var idx = -1
        if (queryTrimmed.isNotEmpty()) {
            idx = lower.indexOf(queryTrimmed)
        }
        if (idx < 0) {
            for (term in terms) {
                if (term.length == 1 && !isCJKChar(term[0])) continue
                val i = lower.indexOf(term)
                if (i >= 0) {
                    idx = i
                    break
                }
            }
        }
        if (idx < 0) idx = 0
        return snippetAround(compacted, idx, maxChars)
    }

    /**
     * 折叠连续空白为一个空格。
     */
    fun compactWhitespace(s: String): String {
        return s.split(Regex("\\s+")).joinToString(" ")
    }

    // ─── 内部辅助 ─────────────────────────────

    private fun snippetAround(text: String, byteIdx: Int, maxChars: Int): String {
        var adjustedIdx = byteIdx.coerceIn(0, text.length)
        val runes = text.toList()
        val pos = text.substring(0, adjustedIdx).length.coerceAtMost(runes.size)
        var start = pos - maxChars / 2
        if (start < 0) start = 0
        var end = start + maxChars
        if (end > runes.size) {
            end = runes.size
            start = (end - maxChars).coerceAtLeast(0)
        }
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < runes.size) "..." else ""
        return prefix + runes.subList(start, end).joinToString("") + suffix
    }

    private fun isCJKChar(ch: Char): Boolean {
        val type = Character.getType(ch)
        return type == Character.OTHER_LETTER.toInt() &&
            (ch in '\u4E00'..'\u9FFF' || // CJK Unified Ideographs
                ch in '\u3040'..'\u309F' || // Hiragana
                ch in '\u30A0'..'\u30FF' || // Katakana
                ch in '\uAC00'..'\uD7AF')   // Hangul Syllables
    }

    private fun Char.isCJK(): Boolean {
        return isCJKChar(this)
    }
}
