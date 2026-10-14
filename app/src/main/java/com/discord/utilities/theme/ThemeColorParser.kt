package com.discord.utilities.theme

/**
 * Theme colors as people actually write them.
 *
 * Aliucord stores signed 32-bit ARGB ints (`-14188314`). Older exports carry the unsigned form
 * (`4283845988`), and hand-written themes use hex. Every accepted form becomes a signed ARGB [Int];
 * anything else is rejected rather than guessed at. Hex is read by hand, so no regex is involved.
 */
object ThemeColorParser {

    /** `null` when [value] is not a color. */
    fun parse(value: String): Int? {
        val text = value.trim()
        return when {
            text.startsWith("#") -> parseHex(text.substring(1))
            text.startsWith("0x") || text.startsWith("0X") -> parseHex(text.substring(2))
            else -> text.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..0xFFFFFFFFL }?.toInt()
        }
    }

    /** `#AARRGGBB`, the form the editor shows and native `attrs` are exported in. */
    fun toHex(color: Int): String = "#" + Integer.toHexString(color).uppercase().padStart(8, '0')

    /** Android's resource hex forms: `RGB`, `ARGB`, `RRGGBB` and `AARRGGBB`. */
    private fun parseHex(digits: String): Int? {
        if (digits.isEmpty() || digits.any { Character.digit(it, 16) < 0 }) return null
        val expanded = when (digits.length) {
            3, 4 -> digits.flatMap { listOf(it, it) }.joinToString("")
            6, 8 -> digits
            else -> return null
        }
        val argb = if (expanded.length == 6) "ff$expanded" else expanded
        return argb.toLong(16).toInt()
    }
}
