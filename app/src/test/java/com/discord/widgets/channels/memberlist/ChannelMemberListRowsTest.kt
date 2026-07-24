package com.discord.widgets.channels.memberlist

import com.discord.api.channel.Channel
import com.discord.api.presence.ClientStatus
import com.discord.api.role.GuildRole
import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMemberListRowsTest {
    private val online = Presence(ClientStatus.ONLINE, null, null)
    private val offline = Presence(ClientStatus.OFFLINE, null, null)

    @Test fun guildRowsPreserveLazyHeadersMembersAndPlaceholders() {
        val role = role(10, "Moderators")
        val user = CoreUser(1, "Ada")
        val rows = ChannelMemberListRows.guild(ChannelMemberListRows.GuildInput(
            "guild-list",
            ownerId = 1,
            rows = listOf(
                LazyMemberListRow.Header("mods", ChannelMemberListRow.Header.Kind.ONLINE, 1, role),
                LazyMemberListRow.Member(1),
                LazyMemberListRow.PlaceholderHeader("lazy"),
                LazyMemberListRow.PlaceholderMember("2"),
            ),
            users = mapOf(1L to user),
            members = mapOf(1L to member(1, role.id, "Captain")),
            presences = mapOf(1L to online),
        ))

        assertTrue(rows[0] is ChannelMemberListRow.RoleHeader)
        assertEquals("Captain", (rows[1] as ChannelMemberListRow.Member).displayName)
        assertTrue((rows[1] as ChannelMemberListRow.Member).isOwner)
        assertTrue(rows[2] is ChannelMemberListRow.PlaceholderHeader)
        assertTrue(rows[3] is ChannelMemberListRow.PlaceholderMember)
        assertEquals(rows.map { it.stableId }.distinct().size, rows.size)
    }

    @Test fun loadedGuildUsesRoleOnlineAndOfflineGroups() {
        val role = role(20, "Staff")
        val users = mapOf(1L to CoreUser(1, "Zed"), 2L to CoreUser(2, "Amy"), 3L to CoreUser(3, "Bob"))
        val rows = ChannelMemberListRows.loadedGuild(
            "guild", 99, users,
            mapOf(1L to member(1, role.id), 2L to member(2), 3L to member(3)),
            mapOf(1L to online, 2L to online, 3L to offline),
            mapOf(role.id to role),
        )
        assertEquals(listOf("Staff", "Zed", "Online", "Amy", "Offline", "Bob"), rows.map {
            when (it) {
                is ChannelMemberListRow.RoleHeader -> it.role.name
                is ChannelMemberListRow.Header -> it.kind.name.lowercase().replaceFirstChar(Char::uppercase)
                is ChannelMemberListRow.Member -> it.displayName
                else -> ""
            }
        })
    }

    @Test fun privateRecipientsSortByUsernameAndUseGroupNicks() {
        val channel = channel(
            recipientIds = listOf(1, 2),
            nicks = listOf(com.discord.api.channel.ChannelRecipientNick(2, "Bee")),
            ownerId = 2,
        )
        val rows = ChannelMemberListRows.privateChannel(
            channel,
            mapOf(1L to CoreUser(1, "zoe", discriminator = 2), 2L to CoreUser(2, "Amy", discriminator = 9)),
            emptyMap(),
        )
        assertEquals(ChannelMemberListRow.Header.Kind.MEMBERS, (rows[0] as ChannelMemberListRow.Header).kind)
        assertEquals(listOf("Bee", "zoe"), rows.filterIsInstance<ChannelMemberListRow.Member>().map { it.displayName })
        assertTrue(rows.filterIsInstance<ChannelMemberListRow.Member>().first().isOwner)
    }

    @Test fun directDmUsesEmbeddedRecipientWhenUserStoreHasNotLoadedIt() {
        val channel = channel(
            type = Channel.DM,
            recipients = listOf(com.discord.api.user.User(7, "Embedded")),
        )
        val members = ChannelMemberListRows.privateChannel(channel, emptyMap(), emptyMap())
            .filterIsInstance<ChannelMemberListRow.Member>()
        assertEquals(listOf("Embedded"), members.map { it.displayName })
    }

    @Test fun actionRowsOnlyAppearWhenSupported() {
        assertTrue(ChannelMemberListRows.privateChannel(channel(), emptyMap(), emptyMap()).none { it is ChannelMemberListRow.AddMember })
        assertTrue(ChannelMemberListRows.privateChannel(channel(), emptyMap(), emptyMap(), canAddMembers = true).first() is ChannelMemberListRow.AddMember)
        assertTrue(ChannelMemberListRows.thread("thread", 0, emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap()).none { it is ChannelMemberListRow.JoinLeaveThread })
        assertTrue(ChannelMemberListRows.thread("thread", 0, emptySet(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), joined = false).first() is ChannelMemberListRow.JoinLeaveThread)
    }

    private fun role(id: Long, name: String) = GuildRole(1, name, id, 0x123456, true, 0, false, false, null, null, null)
    private fun member(id: Long, hoistRoleId: Long = 0, nick: String? = null) =
        GuildMember(0, hoistRoleId, if (hoistRoleId == 0L) null else listOf(hoistRoleId), nick, null, false, null, 1, id)

    private fun channel(
        type: Int = Channel.GROUP_DM,
        recipientIds: List<Long> = emptyList(),
        recipients: List<com.discord.api.user.User>? = null,
        nicks: List<com.discord.api.channel.ChannelRecipientNick>? = null,
        ownerId: Long = 0,
    ) = Channel(
        topic = null,
        type = type,
        guildId = 0,
        name = null,
        lastMessageId = 0,
        id = 100,
        ownerId = ownerId,
        recipientIds = recipientIds,
        recipients = recipients,
        position = 0,
        permissionOverwrites = null,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0,
        applicationId = 0,
        nicks = nicks,
        nsfw = false,
        parentId = 0,
        memberListId = null,
        rateLimitPerUser = 0,
        defaultAutoArchiveDuration = null,
        rtcRegion = null,
        flags = null,
        guildHashes = null,
        availableTags = null,
        appliedTags = null,
        threadMetadata = null,
        messageCount = null,
        memberCount = null,
        memberIdsPreview = null,
        member = null,
    )
}
