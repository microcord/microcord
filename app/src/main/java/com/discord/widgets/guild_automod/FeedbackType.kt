package com.discord.widgets.guild_automod

/**
 * Why the reporter thinks AutoMod got it wrong.
 *
 * [value] is what goes into the analytics event, and it really is the uppercase constant name —
 * declaration order (`BUG`, `ALLOWED`) is the reference's, taken from the synthetic `$VALUES`
 * array rather than the alphabetised decompiler output.
 */
enum class FeedbackType(val value: String) {
    BUG("BUG"),
    ALLOWED("ALLOWED"),
}
