package com.discord.api.guild

import com.discord.utilities.persister.CachedFormat

@CachedFormat
sealed class GuildMaxVideoChannelUsers {
    @CachedFormat
    data class Limited(val limit: Int) : GuildMaxVideoChannelUsers()
    object Unlimited : GuildMaxVideoChannelUsers()
}
