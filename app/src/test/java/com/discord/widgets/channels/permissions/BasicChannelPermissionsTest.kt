package com.discord.widgets.channels.permissions

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.models.user.CoreUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BasicChannelPermissionsTest {
    @Test
    fun privateTogglesOnlyTheEveryoneViewDenial() {
        val public = channel(listOf(PermissionOverwrite(GUILD, PermissionOverwrite.Type.ROLE, 0, Permission.SEND_MESSAGES)))
        assertFalse(BasicChannelPermissions.isPrivate(public))

        val made = BasicChannelPermissions.everyoneOverwriteFor(public, private = true)!!
        assertEquals(Permission.SEND_MESSAGES or Permission.VIEW_CHANNEL, made.deny)
        assertTrue(BasicChannelPermissions.isPrivate(channel(listOf(made))))

        val back = BasicChannelPermissions.everyoneOverwriteFor(channel(listOf(made)), private = false)!!
        assertEquals(Permission.SEND_MESSAGES, back.deny)
        // Nothing else on @everyone: going public removes the overwrite outright.
        val bare = channel(listOf(PermissionOverwrite(GUILD, PermissionOverwrite.Type.ROLE, 0, Permission.VIEW_CHANNEL)))
        assertNull(BasicChannelPermissions.everyoneOverwriteFor(bare, private = false))
    }

    @Test
    fun accessListsAdministratorsThenAllowedRolesThenMembers() {
        val admin = role(10, position = 1, permissions = Permission.ADMINISTRATOR)
        val allowed = role(20, position = 5)
        val ignored = role(30, position = 9)
        val overwrites = listOf(
            PermissionOverwrite(GUILD, PermissionOverwrite.Type.ROLE, 0, Permission.VIEW_CHANNEL),
            PermissionOverwrite(20, PermissionOverwrite.Type.ROLE, Permission.VIEW_CHANNEL, 0),
            PermissionOverwrite(30, PermissionOverwrite.Type.ROLE, Permission.SEND_MESSAGES, 0),
            PermissionOverwrite(40, PermissionOverwrite.Type.MEMBER, Permission.VIEW_CHANNEL, 0),
        )
        val items = BasicChannelPermissions.accessItems(
            channel(overwrites),
            listOf(ignored, allowed, admin, role(GUILD, position = 0)),
            mapOf(40L to CoreUser(40, "member")),
            emptyMap(),
            ownerId = 1,
            canEdit = true,
        )

        assertEquals(listOf(10L, 20L, 40L), items.map { it.permissionOwner.entityId })
        assertTrue(items[0].removeStatus is PermissionOwnerListView.RemoveStatus.CannotRemove)
        assertEquals(PermissionOwnerListView.RemoveStatus.CanRemove, items[1].removeStatus)
        assertEquals(PermissionOwnerListView.RemoveStatus.CanRemove, items[2].removeStatus)
    }

    @Test
    fun syncedWhenOverwritesMatchCountingAMissingEveryoneAsEmpty() {
        val category = channel(listOf(PermissionOverwrite(20, PermissionOverwrite.Type.ROLE, 1, 2)))
        val same = channel(
            listOf(
                PermissionOverwrite(20, PermissionOverwrite.Type.ROLE, 1, 2),
                PermissionOverwrite(GUILD, PermissionOverwrite.Type.ROLE, 0, 0),
            ),
        )
        val different = channel(listOf(PermissionOverwrite(20, PermissionOverwrite.Type.ROLE, 1, 0)))

        assertTrue(PermissionEditor.isSyncedWith(same, category))
        assertFalse(PermissionEditor.isSyncedWith(different, category))
    }

    @Test
    fun modernOverviewOffersBasicAndRemembersAdvanced() {
        val fresh = WidgetChannelSettingsPermissionsOverviewViewModel(false, modern = true).requireViewState()
        val remembered = WidgetChannelSettingsPermissionsOverviewViewModel(false, modern = true, startAdvanced = true)
            .requireViewState()
        assertEquals(
            listOf(
                WidgetChannelSettingsPermissionsOverviewViewModel.Tab.BASIC,
                WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED,
            ),
            fresh.availableTabs,
        )
        assertEquals(WidgetChannelSettingsPermissionsOverviewViewModel.Tab.BASIC, fresh.selectedTab)
        assertEquals(WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED, remembered.selectedTab)
    }

    private fun role(id: Long, position: Int, permissions: Long = 0) =
        GuildRole(
            position = position, name = "role-$id", id = id, color = 0, hoist = false, permissions = permissions,
            mentionable = false, managed = false, icon = null, tags = null, unicodeEmoji = null,
        )

    private fun channel(overwrites: List<PermissionOverwrite>) = Channel(
        topic = null,
        type = Channel.GUILD_TEXT,
        guildId = GUILD,
        name = "channel",
        lastMessageId = 0L,
        id = 100L,
        ownerId = 0L,
        recipientIds = null,
        recipients = null,
        position = 0,
        permissionOverwrites = overwrites,
        userLimit = 0,
        bitrate = 0,
        icon = null,
        originChannelId = 0L,
        applicationId = 0L,
        nicks = null,
        nsfw = false,
        parentId = 0L,
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

    private companion object {
        const val GUILD = 7L
    }
}
