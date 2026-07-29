package com.discord.widgets.guilds.create

sealed class ChannelTemplate(val id: Long?, val name: String) {
    class NormalChannel(name: String) : ChannelTemplate(null, name)
    class SystemChannel(name: String) : ChannelTemplate(SYSTEM_CHANNEL_ID, name)

    private companion object {
        const val SYSTEM_CHANNEL_ID = 11L
    }
}
