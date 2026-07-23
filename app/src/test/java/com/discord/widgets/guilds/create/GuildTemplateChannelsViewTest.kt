package com.discord.widgets.guilds.create

import com.discord.R
import com.discord.api.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class GuildTemplateChannelsViewTest {
    @Test
    fun mapsTemplateChannelIcons() {
        assertEquals(R.drawable.ic_chevron_down_grey_12dp, GuildTemplateChannelsView.iconForChannelType(Channel.CATEGORY))
        assertEquals(R.drawable.ic_channel_voice_16dp, GuildTemplateChannelsView.iconForChannelType(Channel.GUILD_VOICE))
        assertEquals(R.drawable.ic_channel_text_16dp, GuildTemplateChannelsView.iconForChannelType(Channel.GUILD_TEXT))
    }
}
