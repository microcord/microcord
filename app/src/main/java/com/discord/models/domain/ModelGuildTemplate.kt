package com.discord.models.domain

import com.discord.api.guild.Guild
import com.discord.api.user.User

data class ModelGuildTemplate(
    val creatorId: Long,
    val creator: User?,
    val sourceGuildId: Long,
    val serializedSourceGuild: Guild?,
    val createdAt: String,
    val updatedAt: String,
    val usageCount: Int,
    val code: String,
    val name: String,
    val description: String,
)
