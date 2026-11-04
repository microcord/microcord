package com.discord.utilities.channel

import com.discord.api.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Test

class GuildChannelIconTest {
    private fun channel(type: Int, flags: Long? = null, nsfw: Boolean = false) = Channel(
        topic = null, type = type, guildId = 1, name = "c", lastMessageId = 0, id = 10,
        ownerId = 0, recipientIds = null, recipients = null, position = 0,
        permissionOverwrites = null, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = nsfw,
        parentId = 0, memberListId = null, rateLimitPerUser = 0, defaultAutoArchiveDuration = null,
        rtcRegion = null, flags = flags, guildHashes = null, availableTags = null,
        appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )

    private fun typeOf(channel: Channel, locked: Boolean = false, isRules: Boolean = false) =
        getChannelType(channel, locked, isRules)

    @Test
    fun `the rules channel wins over its own type and locked state`() {
        assertEquals(
            GuildChannelIconType.Special.Rules,
            typeOf(channel(Channel.GUILD_TEXT), locked = true, isRules = true),
        )
        assertEquals(
            GuildChannelIconType.Special.Rules,
            typeOf(channel(Channel.GUILD_ANNOUNCEMENT, nsfw = true), isRules = true),
        )
    }

    @Test
    fun `moderator report beats rules`() {
        val report = channel(Channel.GUILD_TEXT, flags = Channel.FLAG_IS_MODERATOR_REPORT_CHANNEL)
        assertEquals(GuildChannelIconType.Special.ModeratorReport, typeOf(report, isRules = true))
    }

    @Test
    fun `nsfw outranks spoiler which outranks locked`() {
        val spoiler = Channel.FLAG_IS_SPOILER_CHANNEL
        assertEquals(
            GuildChannelIconType.NSFW.Text,
            typeOf(channel(Channel.GUILD_TEXT, spoiler, nsfw = true), locked = true),
        )
        assertEquals(
            GuildChannelIconType.Special.SpoilerText,
            typeOf(channel(Channel.GUILD_TEXT, spoiler), locked = true),
        )
        assertEquals(GuildChannelIconType.Locked.Text, typeOf(channel(Channel.GUILD_TEXT), locked = true))
    }

    @Test
    fun `game invites forums keep their own icon when locked`() {
        val invites = Channel.FLAG_IS_GAME_INVITES_CHANNEL
        assertEquals(GuildChannelIconType.Special.GameInvites, typeOf(channel(Channel.GUILD_FORUM, invites)))
        assertEquals(
            GuildChannelIconType.Special.LockedGameInvites,
            typeOf(channel(Channel.GUILD_FORUM, invites), locked = true),
        )
        assertEquals(GuildChannelIconType.Forum, typeOf(channel(Channel.GUILD_FORUM)))
    }

    @Test
    fun `media channels and locked voice get their variants`() {
        assertEquals(GuildChannelIconType.Media, typeOf(channel(Channel.GUILD_MEDIA)))
        assertEquals(GuildChannelIconType.Special.NsfwMedia, typeOf(channel(Channel.GUILD_MEDIA, nsfw = true)))
        assertEquals(GuildChannelIconType.Special.LockedMedia, typeOf(channel(Channel.GUILD_MEDIA), locked = true))
        assertEquals(GuildChannelIconType.Locked.Voice, typeOf(channel(Channel.GUILD_VOICE), locked = true))
        assertEquals(
            GuildChannelIconType.Special.SpoilerVoice,
            typeOf(channel(Channel.GUILD_VOICE, Channel.FLAG_IS_SPOILER_CHANNEL)),
        )
    }

    @Test
    fun `plain channels have no special icon`() {
        assertEquals(GuildChannelIconType.Text, typeOf(channel(Channel.GUILD_TEXT)))
        assertEquals(GuildChannelIconType.Announcements, typeOf(channel(Channel.GUILD_ANNOUNCEMENT)))
    }
}
