package com.discord.widgets.guilds.join

import com.discord.api.guild.welcome.GuildWelcomeChannel

sealed class ChannelItem(val type: Int) {
    data class ChannelData(
        val welcomeChannel: GuildWelcomeChannel,
        val dismissSheet: () -> Unit,
        val guildId: Long,
        val goToChannel: (Long, Int) -> Unit,
        val index: Int,
    ) : ChannelItem(TYPE_CHANNEL)

    companion object {
        const val TYPE_CHANNEL = 0
    }
}
