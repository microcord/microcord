package com.discord.api.soundboard

import com.discord.api.user.User

data class SoundboardSound(
    val soundId: Long? = null,
    val name: String? = null,
    val volume: Float? = null,
    val emojiId: Long? = null,
    val emojiName: String? = null,
    val guildId: Long? = null,
    val available: Boolean? = null,
    val user: User? = null,
    val userId: Long? = null,
)
