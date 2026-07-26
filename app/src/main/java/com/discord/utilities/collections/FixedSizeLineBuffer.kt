package com.discord.utilities.collections

/**
 * A thread-safe rolling buffer of text lines capped by a total character budget.
 *
 * Used by the diagnostic log surfaces so an unbounded stream of lines cannot grow without limit.
 */
class FixedSizeLineBuffer(private val maxCharacterCount: Int) {

    private val output = ArrayDeque<String>()
    private val sync = Any()
    private var outputLength = 0

    fun addLine(line: String) {
        synchronized(sync) {
            output.add(line)
            outputLength += line.length + 1
            while (outputLength > maxCharacterCount) {
                val removed = output.removeFirstOrNull() ?: break
                outputLength -= removed.length - 1
            }
        }
    }

    fun appendString(sb: StringBuilder) {
        synchronized(sync) {
            for (line in output) {
                sb.append(line)
                sb.append('\n')
            }
        }
    }

    fun getString(): String = synchronized(sync) { output.joinToString("\n") }
}
