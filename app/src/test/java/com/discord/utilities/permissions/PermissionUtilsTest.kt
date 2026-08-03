package com.discord.utilities.permissions

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.member.GuildMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionUtilsTest {

    // region @everyone fallback

    @Test fun `missing everyone role falls back to DEFAULT rather than NONE`() {
        val permissions = PermissionUtils.computeNonThreadPermissions(
            userId = USER, guildId = GUILD, guildOwnerId = OWNER,
            member = member(), guildRoles = emptyMap(), permissionOverwrites = emptyList(),
        )
        assertEquals(Permission.DEFAULT, permissions)
    }

    @Test fun `null role map falls back to DEFAULT`() {
        val permissions = PermissionUtils.computeNonThreadPermissions(
            userId = USER, guildId = GUILD, guildOwnerId = OWNER,
            member = member(), guildRoles = null, permissionOverwrites = null,
        )
        assertEquals(Permission.DEFAULT, permissions)
    }

    @Test fun `present everyone role replaces the fallback`() {
        val roles = mapOf(GUILD to role(GUILD, Permission.VIEW_CHANNEL))
        val permissions = PermissionUtils.computeNonThreadPermissions(
            userId = USER, guildId = GUILD, guildOwnerId = OWNER,
            member = member(), guildRoles = roles, permissionOverwrites = emptyList(),
        )
        assertEquals(Permission.VIEW_CHANNEL, permissions)
    }

    // endregion

    // region owner / administrator short circuits

    @Test fun `owner gets every permission regardless of roles`() {
        val permissions = PermissionUtils.computeNonThreadPermissions(
            userId = OWNER, guildId = GUILD, guildOwnerId = OWNER,
            member = null, guildRoles = emptyMap(), permissionOverwrites = emptyList(),
        )
        assertEquals(Permission.ALL, permissions)
    }

    @Test fun `administrator role beats every deny overwrite`() {
        val roles = mapOf(
            GUILD to role(GUILD, Permission.NONE),
            ADMIN_ROLE to role(ADMIN_ROLE, Permission.ADMINISTRATOR),
        )
        val permissions = PermissionUtils.computeNonThreadPermissions(
            userId = USER, guildId = GUILD, guildOwnerId = OWNER,
            member = member(ADMIN_ROLE), guildRoles = roles,
            permissionOverwrites = listOf(overwrite(GUILD, deny = Permission.VIEW_CHANNEL)),
        )
        assertEquals(Permission.ALL, permissions)
    }

    // endregion

    // region overwrite ordering

    @Test fun `overwrites apply everyone then roles then member`() {
        val roles = mapOf(
            GUILD to role(GUILD, Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES),
            ROLE_A to role(ROLE_A, Permission.NONE),
            ROLE_B to role(ROLE_B, Permission.NONE),
        )
        val overwrites = listOf(
            // @everyone overwrite removes SEND_MESSAGES for the whole channel...
            overwrite(GUILD, deny = Permission.SEND_MESSAGES),
            // ...one of the member's roles denies VIEW_CHANNEL...
            overwrite(ROLE_A, deny = Permission.VIEW_CHANNEL),
            // ...another allows SEND_MESSAGES back, and role allows beat role denies...
            overwrite(ROLE_B, allow = Permission.SEND_MESSAGES),
            // ...and the member overwrite is applied last and wins outright.
            overwrite(USER, allow = Permission.VIEW_CHANNEL, deny = Permission.CONNECT),
        )
        val permissions = PermissionUtils.computeNonThreadPermissions(
            userId = USER, guildId = GUILD, guildOwnerId = OWNER,
            member = member(ROLE_A, ROLE_B), guildRoles = roles,
            permissionOverwrites = overwrites,
        )

        assertTrue(PermissionUtils.can(Permission.VIEW_CHANNEL, permissions))
        assertTrue(PermissionUtils.can(Permission.SEND_MESSAGES, permissions))
        assertFalse(PermissionUtils.can(Permission.CONNECT, permissions))
    }

    @Test fun `role deny wins when no other role allows it back`() {
        val roles = mapOf(
            GUILD to role(GUILD, Permission.VIEW_CHANNEL),
            ROLE_A to role(ROLE_A, Permission.NONE),
        )
        val permissions = PermissionUtils.computeNonThreadPermissions(
            userId = USER, guildId = GUILD, guildOwnerId = OWNER,
            member = member(ROLE_A), guildRoles = roles,
            permissionOverwrites = listOf(overwrite(ROLE_A, deny = Permission.VIEW_CHANNEL)),
        )
        assertFalse(PermissionUtils.can(Permission.VIEW_CHANNEL, permissions))
    }

    @Test fun `computeChannelOverwrite reports only the overwrite delta`() {
        val overwrites = listOf(
            overwrite(GUILD, allow = Permission.VIEW_CHANNEL),
            overwrite(USER, allow = Permission.SEND_MESSAGES),
        )
        val permissions = PermissionUtils.computeChannelOverwrite(USER, GUILD, member(), overwrites)
        assertEquals(Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES, permissions)
    }

    // endregion

    // region thread derivation

    @Test fun `private thread is invisible until joined without manage threads`() {
        val roles = mapOf(
            GUILD to role(GUILD, Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES_IN_THREADS),
        )
        val thread = channel(id = THREAD, type = Channel.PRIVATE_THREAD)
        val parent = channel(id = CHANNEL, type = Channel.GUILD_TEXT)

        val notJoined = PermissionUtils.computeThreadPermissions(
            USER, thread, parent, OWNER, member(), roles, hasJoined = false,
        )
        val joined = PermissionUtils.computeThreadPermissions(
            USER, thread, parent, OWNER, member(), roles, hasJoined = true,
        )

        assertEquals(Permission.NONE, notJoined)
        assertTrue(PermissionUtils.can(Permission.SEND_MESSAGES, joined))
    }

    @Test fun `private thread stays visible for thread managers`() {
        val roles = mapOf(GUILD to role(GUILD, Permission.VIEW_CHANNEL or Permission.MANAGE_THREADS))
        val permissions = PermissionUtils.computeThreadPermissions(
            USER,
            channel(id = THREAD, type = Channel.PRIVATE_THREAD),
            channel(id = CHANNEL, type = Channel.GUILD_TEXT),
            OWNER, member(), roles, hasJoined = false,
        )
        assertTrue(PermissionUtils.can(Permission.VIEW_CHANNEL, permissions))
    }

    @Test fun `sending in a thread is gated on send messages in threads`() {
        val parent = channel(id = CHANNEL, type = Channel.GUILD_TEXT)
        val thread = channel(id = THREAD, type = Channel.PUBLIC_THREAD)

        val withoutThreadSend = PermissionUtils.computeThreadPermissions(
            USER, thread, parent, OWNER, member(),
            mapOf(GUILD to role(GUILD, Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES)),
            hasJoined = true,
        )
        val withThreadSend = PermissionUtils.computeThreadPermissions(
            USER, thread, parent, OWNER, member(),
            mapOf(GUILD to role(GUILD, Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES_IN_THREADS)),
            hasJoined = true,
        )

        // The parent's SEND_MESSAGES is explicitly stripped when it does not carry through.
        assertFalse(PermissionUtils.can(Permission.SEND_MESSAGES, withoutThreadSend))
        assertTrue(PermissionUtils.can(Permission.SEND_MESSAGES, withThreadSend))
    }

    @Test fun `thread without a parent channel resolves to no permissions`() {
        val permissions = PermissionUtils.computePermissions(
            userId = USER,
            channel = channel(id = THREAD, type = Channel.PUBLIC_THREAD),
            parentChannel = null,
            guildOwnerId = OWNER,
            member = member(),
            guildRoles = mapOf(GUILD to role(GUILD, Permission.ALL)),
            stageInstances = null,
            hasJoinedThread = true,
        )
        assertEquals(Permission.NONE, permissions)
    }

    // endregion

    // region lurkers, stages, and timeouts

    @Test fun `lurker in a public stage keeps the stage allow list`() {
        val stage = channel(id = CHANNEL, type = Channel.GUILD_STAGE_VOICE)
        val permissions = PermissionUtils.computePermissions(
            userId = USER, channel = stage, parentChannel = null, guildOwnerId = OWNER,
            member = lurker(), guildRoles = mapOf(GUILD to role(GUILD, Permission.ALL)),
            stageInstances = mapOf(CHANNEL to stageInstance(StageInstancePrivacyLevel.PUBLIC)),
            hasJoinedThread = false,
        )
        assertEquals(Permission.AllowList.LURKER_STAGE_CHANNEL, permissions)
        assertTrue(PermissionUtils.can(Permission.SPEAK, permissions))
        assertTrue(PermissionUtils.can(Permission.REQUEST_TO_SPEAK, permissions))
    }

    @Test fun `lurker in a guild-only stage keeps only the default allow list`() {
        val stage = channel(id = CHANNEL, type = Channel.GUILD_STAGE_VOICE)
        val permissions = PermissionUtils.computePermissions(
            userId = USER, channel = stage, parentChannel = null, guildOwnerId = OWNER,
            member = lurker(), guildRoles = mapOf(GUILD to role(GUILD, Permission.ALL)),
            stageInstances = mapOf(CHANNEL to stageInstance(StageInstancePrivacyLevel.GUILD_ONLY)),
            hasJoinedThread = false,
        )
        assertEquals(Permission.AllowList.LURKER_DEFAULT, permissions)
    }

    @Test fun `lurker in a text channel keeps only the default allow list`() {
        val permissions = PermissionUtils.computePermissions(
            userId = USER, channel = channel(id = CHANNEL, type = Channel.GUILD_TEXT),
            parentChannel = null, guildOwnerId = OWNER, member = lurker(),
            guildRoles = mapOf(GUILD to role(GUILD, Permission.ALL)),
            stageInstances = null, hasJoinedThread = false,
        )
        assertEquals(Permission.AllowList.LURKER_DEFAULT, permissions)
    }

    @Test fun `timed out member keeps only the communications-disabled allow list`() {
        val permissions = PermissionUtils.computePermissions(
            userId = USER, channel = channel(id = CHANNEL, type = Channel.GUILD_TEXT),
            parentChannel = null, guildOwnerId = OWNER,
            member = member().copy(communicationDisabledUntil = UtcDateTime(FAR_FUTURE)),
            guildRoles = mapOf(GUILD to role(GUILD, Permission.ALL)),
            stageInstances = null, hasJoinedThread = false,
        )
        assertEquals(Permission.AllowList.COMMUNICATIONS_DISABLED_DEFAULT, permissions)
    }

    // endregion

    // region MFA elevation

    @Test fun `elevated permissions require MFA only when the guild demands it`() {
        assertTrue(PermissionUtils.isElevated(Permission.BAN_MEMBERS, userMfaEnabled = false, guildMfaLevel = 0))
        assertFalse(PermissionUtils.isElevated(Permission.BAN_MEMBERS, userMfaEnabled = false, guildMfaLevel = 1))
        assertTrue(PermissionUtils.isElevated(Permission.BAN_MEMBERS, userMfaEnabled = true, guildMfaLevel = 1))
    }

    @Test fun `non-elevated permissions never require MFA`() {
        assertTrue(
            PermissionUtils.isElevated(Permission.CHANGE_NICKNAME, userMfaEnabled = false, guildMfaLevel = 1),
        )
    }

    @Test fun `canAndIsElevated needs both the bit and the elevation`() {
        val permissions = Permission.BAN_MEMBERS
        assertTrue(PermissionUtils.canAndIsElevated(Permission.BAN_MEMBERS, permissions, true, 1))
        assertFalse(PermissionUtils.canAndIsElevated(Permission.BAN_MEMBERS, permissions, false, 1))
        assertFalse(PermissionUtils.canAndIsElevated(Permission.KICK_MEMBERS, permissions, true, 1))
    }

    @Test fun `manage nicknames alone is enough to manage members without MFA`() {
        assertTrue(
            PermissionUtils.canManageGuildMembers(
                isOwner = false, userMfaEnabled = false, guildMfaLevel = 1,
                permissions = Permission.MANAGE_NICKNAMES,
            ),
        )
        assertFalse(
            PermissionUtils.canManageGuildMembers(
                isOwner = false, userMfaEnabled = false, guildMfaLevel = 1,
                permissions = Permission.KICK_MEMBERS,
            ),
        )
    }

    // endregion

    // region access

    @Test fun `voice access needs connect while private channels always pass`() {
        val voice = channel(id = CHANNEL, type = Channel.GUILD_VOICE)
        assertFalse(PermissionUtils.hasAccess(voice, Permission.VIEW_CHANNEL))
        assertTrue(PermissionUtils.hasAccess(voice, Permission.VIEW_CHANNEL or Permission.CONNECT))
        assertTrue(PermissionUtils.hasAccess(channel(id = CHANNEL, type = Channel.DM), Permission.NONE))
    }

    @Test fun `write access needs view and send outside private channels`() {
        val text = channel(id = CHANNEL, type = Channel.GUILD_TEXT)
        assertFalse(PermissionUtils.hasAccessWrite(text, Permission.VIEW_CHANNEL))
        assertTrue(
            PermissionUtils.hasAccessWrite(text, Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES),
        )
        assertTrue(PermissionUtils.hasAccessWrite(channel(id = CHANNEL, type = Channel.DM), null))
    }

    @Test fun `slow mode bypass differs between sending and thread creation`() {
        assertTrue(
            PermissionUtils.hasBypassSlowmodePermissions(
                Permission.MANAGE_MESSAGES, SlowModeType.MESSAGE_SEND,
            ),
        )
        assertFalse(
            PermissionUtils.hasBypassSlowmodePermissions(
                Permission.MANAGE_MESSAGES, SlowModeType.THREAD_CREATE,
            ),
        )
        assertTrue(
            PermissionUtils.hasBypassSlowmodePermissions(
                Permission.MANAGE_THREADS, SlowModeType.THREAD_CREATE,
            ),
        )
    }

    // endregion

    // region everyone checks

    @Test fun `canEveryone respects the everyone role and channel denies`() {
        val roles = mapOf(GUILD to role(GUILD, Permission.VIEW_CHANNEL))
        val open = channel(id = CHANNEL, type = Channel.GUILD_TEXT)
        val closed = channel(
            id = CHANNEL, type = Channel.GUILD_TEXT,
            overwrites = listOf(overwrite(GUILD, deny = Permission.VIEW_CHANNEL)),
        )

        assertTrue(PermissionUtils.canEveryone(Permission.VIEW_CHANNEL, open, null, roles))
        assertFalse(PermissionUtils.canEveryone(Permission.VIEW_CHANNEL, closed, null, roles))
        assertFalse(PermissionUtils.canEveryone(Permission.SEND_MESSAGES, open, null, roles))
    }

    @Test fun `canEveryone delegates public threads to the parent and rejects private ones`() {
        val roles = mapOf(GUILD to role(GUILD, Permission.VIEW_CHANNEL))
        val parent = channel(id = CHANNEL, type = Channel.GUILD_TEXT)

        assertTrue(
            PermissionUtils.canEveryone(
                Permission.VIEW_CHANNEL, channel(id = THREAD, type = Channel.PUBLIC_THREAD), parent, roles,
            ),
        )
        assertFalse(
            PermissionUtils.canEveryone(
                Permission.VIEW_CHANNEL, channel(id = THREAD, type = Channel.PRIVATE_THREAD), parent, roles,
            ),
        )
        assertFalse(
            PermissionUtils.canEveryone(
                Permission.VIEW_CHANNEL, channel(id = THREAD, type = Channel.PUBLIC_THREAD), null, roles,
            ),
        )
    }

    @Test fun `canRole applies the role overwrite before testing`() {
        val guildRole = role(ROLE_A, Permission.VIEW_CHANNEL)
        assertTrue(PermissionUtils.canRole(Permission.VIEW_CHANNEL, guildRole, null))
        assertFalse(
            PermissionUtils.canRole(
                Permission.VIEW_CHANNEL, guildRole, overwrite(ROLE_A, deny = Permission.VIEW_CHANNEL),
            ),
        )
        assertTrue(
            PermissionUtils.canRole(
                Permission.SEND_MESSAGES, guildRole, overwrite(ROLE_A, allow = Permission.SEND_MESSAGES),
            ),
        )
        assertFalse(PermissionUtils.canRole(Permission.VIEW_CHANNEL, null, null))
    }

    @Test fun `canEveryoneRole uses the everyone role and its channel overwrite`() {
        val roles = mapOf(GUILD to role(GUILD, Permission.VIEW_CHANNEL))
        val closed = channel(
            id = CHANNEL, type = Channel.GUILD_TEXT,
            overwrites = listOf(overwrite(GUILD, deny = Permission.VIEW_CHANNEL)),
        )
        assertTrue(
            PermissionUtils.canEveryoneRole(
                Permission.VIEW_CHANNEL, channel(id = CHANNEL, type = Channel.GUILD_TEXT), roles,
            ),
        )
        assertFalse(PermissionUtils.canEveryoneRole(Permission.VIEW_CHANNEL, closed, roles))
    }

    // endregion

    private companion object {
        const val GUILD = 1L
        const val CHANNEL = 10L
        const val THREAD = 11L
        const val USER = 7L
        const val OWNER = 9L
        const val ROLE_A = 2L
        const val ROLE_B = 3L
        const val ADMIN_ROLE = 4L
        const val FAR_FUTURE = 4_102_444_800_000L

        fun role(id: Long, permissions: Long) =
            GuildRole(0, "role", id, 0, false, permissions, false, false, null, null, null)

        fun member(vararg roles: Long) = GuildMember(
            color = 0, hoistRoleId = 0, roles = roles.toList(), nick = null, premiumSince = null,
            pending = false, joinedAt = UtcDateTime(0), guildId = GUILD, userId = USER,
        )

        fun lurker() = member().copy(joinedAt = null)

        fun overwrite(id: Long, allow: Long = 0, deny: Long = 0) =
            PermissionOverwrite(id, PermissionOverwrite.Type.ROLE, allow, deny)

        fun stageInstance(privacyLevel: StageInstancePrivacyLevel) =
            StageInstance(1, GUILD, CHANNEL, "topic", privacyLevel, null)

        fun channel(
            id: Long,
            type: Int,
            overwrites: List<PermissionOverwrite> = emptyList(),
        ) = Channel(
            topic = null, type = type, guildId = GUILD, name = "channel", lastMessageId = 0,
            id = id, ownerId = 0, recipientIds = null, recipients = null, position = 0,
            permissionOverwrites = overwrites, userLimit = 0, bitrate = 0, icon = null,
            originChannelId = 0, applicationId = 0, nicks = null, nsfw = false, parentId = 0,
            memberListId = null, rateLimitPerUser = 0, defaultAutoArchiveDuration = null,
            rtcRegion = null, flags = null, guildHashes = null, availableTags = null,
            appliedTags = null, threadMetadata = null, messageCount = null, memberCount = null,
            memberIdsPreview = null, member = null,
        )
    }
}
