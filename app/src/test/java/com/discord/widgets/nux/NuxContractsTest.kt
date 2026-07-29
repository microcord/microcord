package com.discord.widgets.nux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NuxContractsTest {

    @Test
    fun `template lists preserve 126_21 order`() {
        assertEquals(
            listOf(
                GuildTemplate.Gaming,
                GuildTemplate.SchoolClub,
                GuildTemplate.StudyGroup,
                GuildTemplate.Friends,
                GuildTemplate.ArtistsAndCreators,
                GuildTemplate.LocalCommunity,
            ),
            GuildTemplateTypes.NUX,
        )
        assertEquals(
            listOf(
                GuildTemplate.HubStudyGroup,
                GuildTemplate.HubSchoolClub,
                GuildTemplate.Class,
                GuildTemplate.Social,
                GuildTemplate.SubjectOrMajor,
                GuildTemplate.Dorm,
            ),
            GuildTemplateTypes.HUB,
        )
        assertSame(
            com.discord.widgets.guilds.create.StockGuildTemplate.GAMING_GROUP,
            GuildTemplate.Gaming.templateType,
        )
        assertSame(
            com.discord.widgets.guilds.create.CreateGuildTrigger.NUF,
            CreateGuildTrigger.NUF,
        )
    }

    @Test
    fun `post registration join accepts codes and Discord invite links`() {
        assertEquals("abc_123-X", WidgetNuxPostRegistrationJoin.parseInviteCode(" abc_123-X "))
        assertEquals("abc123", WidgetNuxPostRegistrationJoin.parseInviteCode("discord.gg/abc123"))
        assertEquals("abc123", WidgetNuxPostRegistrationJoin.parseInviteCode("https://discord.com/invite/abc123"))
        assertNull(WidgetNuxPostRegistrationJoin.parseInviteCode("https://example.com/abc123"))
    }
}
