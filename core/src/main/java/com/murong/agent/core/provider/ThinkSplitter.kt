package com.murong.agent.core.provider

/**
 * ThinkSplitter — 内联 <think>...</think> 标签解析器
 *
 * 某些 Provider（如 MiniMax-M3）不通过 reasoning_content 字段返回推理过程，
 * 而是把 chain-of-thought 内联在 <think> 标签里。本类流式地将标签内内容
 * 剥离为 reasoning，标签外的作为 text。
 *
 * 它只在 turn 最开头出现 <think> 时激活，所以回答中普通提及该标签不会被劫持。
 *
 * 移植自 Reasonix (DeepSeek-Reasonix) internal/provider/openai/think.go
 */
class ThinkSplitter {
    private var state: ThinkState = ThinkState.PROBE
    private val buf = StringBuilder()

    /**
     * 推送一个流式文本块，返回 (reasoning, text) 两部分。
     * @param s 流式文本块
     * @return Pair(reasoning 文本, 普通文本)
     */
    fun push(s: String): Pair<String, String> {
        return when (state) {
            ThinkState.PASSTHROUGH -> {
                Pair("", s)
            }
            ThinkState.INSIDE -> {
                scanClose(s)
            }
            ThinkState.PROBE -> {
                buf.append(s)
                val trimmed = buf.toString().trimStart(' ', '\t', '\r', '\n')
                when {
                    trimmed.length < THINK_OPEN.length -> {
                        if (THINK_OPEN.startsWith(trimmed)) {
                            Pair("", "") // 可能后续数据补全为 <think>
                        } else {
                            Pair("", drainPassthrough())
                        }
                    }
                    trimmed.startsWith(THINK_OPEN) -> {
                        state = ThinkState.INSIDE
                        buf.clear()
                        scanClose(trimmed.substring(THINK_OPEN.length))
                    }
                    else -> {
                        Pair("", drainPassthrough())
                    }
                }
            }
        }
    }

    /**
     * 清空缓冲区——当流结束时调用，处理未闭合的 <think> 块。
     * 未闭合的 <think> 块视为 reasoning，其他视为 text。
     */
    fun flush(): Pair<String, String> {
        if (buf.isEmpty()) return Pair("", "")
        val out = buf.toString()
        buf.clear()
        return if (state == ThinkState.INSIDE) {
            Pair(out, "")
        } else {
            Pair("", out)
        }
    }

    private fun scanClose(s: String): Pair<String, String> {
        buf.append(s)
        val idx = buf.indexOf(THINK_CLOSE)
        if (idx >= 0) {
            val r = buf.substring(0, idx)
            val rest = buf.substring(idx + THINK_CLOSE.length)
                .trimStart(' ', '\t', '\r', '\n')
            buf.clear()
            state = ThinkState.PASSTHROUGH
            return Pair(r, rest)
        }
        val keep = markerSuffixLen(buf.toString(), THINK_CLOSE)
        val r = buf.substring(0, buf.length - keep)
        buf.delete(0, buf.length - keep)
        return Pair(r, "")
    }

    private fun drainPassthrough(): String {
        state = ThinkState.PASSTHROUGH
        val out = buf.toString()
        buf.clear()
        return out
    }

    private enum class ThinkState {
        PROBE,
        INSIDE,
        PASSTHROUGH
    }

    companion object {
        private const val THINK_OPEN = "<think>"
        private const val THINK_CLOSE = "</think>"

        /**
         * markerSuffixLen 返回 s 的最长真后缀长度，该后缀是 marker 的前缀。
         * 用于保留可能跨数据块抵达的标签尾部。
         */
        fun markerSuffixLen(s: String, marker: String): Int {
            val max = (marker.length - 1).coerceAtMost(s.length)
            for (n in max downTo 1) {
                if (marker.startsWith(s.substring(s.length - n))) {
                    return n
                }
            }
            return 0
        }
    }
}
