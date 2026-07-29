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

    @Test
    fun `stock templates retain wire order and named guild format`() {
        assertEquals(
            listOf(
                "FRIEND_GROUP", "STUDY_GROUP", "GAMING_GROUP", "CONTENT_CREATOR", "CLUB",
                "LOCAL_COMMUNITY", "HUB_SCHOOL_CLUB", "HUB_STUDY_GROUP", "CLASS", "SOCIAL",
                "MAJOR", "DORM", "CREATE",
            ),
            StockGuildTemplate.values().map { it.name },
        )
        assertEquals(
            "Manti's server",
            WidgetGuildCreateViewModel.formatGuildName("{username}'s server", "Manti"),
        )
    }

    @Test
    fun `clone preview groups children after their parent by id`() {
        assertEquals(1_000_000L, WidgetGuildClone.templateChannelOrder(100, 0))
        assertEquals(1_000_007L, WidgetGuildClone.templateChannelOrder(7, 100))
    }
}
