package com.discord.widgets.chat.input

/**
 * The autocomplete candidates for one in-progress token in the composer.
 *
 * [tokenIdentifier] is the character that opened it (`@`, `#`, `:`, `/`) and [token] is what has
 * been typed after it, so a consumer can both filter and know how much text to replace when a tag
 * is chosen. [tags] is generic because the candidate type differs per identifier — users, channels,
 * emoji, commands.
 */
data class WidgetChatInputMentionsTags<T>(
    val tags: List<T>,
    val tokenIdentifier: Char,
    val token: String,
)
