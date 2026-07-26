package com.discord.models.domain.emoji

import com.discord.api.user.User

/** REST shape of a guild emoji, including the uploader; see `guilds/{id}/emojis`. */
data class ModelEmojiGuild(
    val id: Long,
    val name: String,
    val managed: Boolean,
    val roles: List<Long>,
    val requiredColons: Boolean,
    val user: User,
    val animated: Boolean,
    val available: Boolean,
)
