package com.discord.widgets.chat.input.models

import com.discord.widgets.chat.input.autocomplete.Autocompletable

/**
 * Which candidate each character range of the composer currently resolves to.
 *
 * Kept alongside the [input] it was computed from so a stale map is never applied to text that has
 * since changed — the composer compares [input] before drawing pills or building the send payload.
 */
data class ChatInputMentionsMap(
    val input: CharSequence,
    val mentions: Map<IntRange, Autocompletable>,
)
