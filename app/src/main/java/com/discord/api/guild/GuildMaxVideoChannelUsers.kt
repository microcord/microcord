package com.discord.api.guild

sealed class GuildMaxVideoChannelUsers {
    data class Limited(val limit: Int) : GuildMaxVideoChannelUsers()
    object Unlimited : GuildMaxVideoChannelUsers()
}
