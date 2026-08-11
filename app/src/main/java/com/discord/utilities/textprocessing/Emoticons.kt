package com.discord.utilities.textprocessing

/**
 * Replaces the emoticons the reference converts on send, leaving anything inside code spans or
 * fences alone.
 */
object Emoticons {

    private val REPLACEMENTS = listOf(
        ":)" to "🙂", ":-)" to "🙂",
        ":(" to "🙁", ":-(" to "🙁",
        ":D" to "😃", ":-D" to "😃",
        ";)" to "😉", ";-)" to "😉",
        ":P" to "😛", ":-P" to "😛", ":p" to "😛",
        ":O" to "😮", ":-O" to "😮", ":o" to "😮",
        ":/" to "😕", ":-/" to "😕",
        ":'(" to "😢",
        ":|" to "😐", ":-|" to "😐",
        "<3" to "❤️",
        ":*" to "😘", ":-*" to "😘",
    )

    private val CODE = Regex("```[\\s\\S]*?```|`[^`\\n]*`")

    fun convert(content: String): String {
        if (content.isEmpty()) return content
        val protected = CODE.findAll(content).map { it.range }.toList()
        val out = StringBuilder(content.length)
        var index = 0
        while (index < content.length) {
            val span = protected.firstOrNull { index in it }
            if (span != null) {
                out.append(content, span.first, span.last + 1)
                index = span.last + 1
                continue
            }
            val match = REPLACEMENTS.firstOrNull { (token, _) ->
                content.startsWith(token, index) && isStandalone(content, index, token.length)
            }
            if (match != null) {
                out.append(match.second)
                index += match.first.length
            } else {
                out.append(content[index])
                index++
            }
        }
        return out.toString()
    }

    /** Only converts a token that stands on its own, so a URL or `:pensive:` survives. */
    private fun isStandalone(content: String, start: Int, length: Int): Boolean {
        val before = content.getOrNull(start - 1)
        val after = content.getOrNull(start + length)
        return (before == null || before.isWhitespace()) && (after == null || after.isWhitespace())
    }
}
