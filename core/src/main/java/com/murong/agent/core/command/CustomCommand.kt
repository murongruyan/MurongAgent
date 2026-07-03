package com.murong.agent.core.command

data class CustomCommand(
    val name: String,
    val description: String,
    val argumentHint: String,
    val body: String
) {
    fun expand(args: String): String {
        val trimmedArgs = args.trim()
        val parts = if (trimmedArgs.isBlank()) emptyList()
            else trimmedArgs.split(Regex("\\s+"), 10)
        var result = body

        val dollar = '\u0024'

        result = result.replace("$dollar$dollar", "\u0000")

        for (i in 1..9) {
            val idx = i - 1
            val replacement = if (idx < parts.size) parts[idx] else ""
            result = result.replace("$dollar" + i.toString(), replacement)
        }

        result = result.replace("$dollar" + "ARGUMENTS", trimmedArgs)

        result = result.replace("\u0000", dollar.toString())

        return result
    }
}
