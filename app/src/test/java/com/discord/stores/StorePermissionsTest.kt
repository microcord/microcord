package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.models.member.GuildMember
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.schedulers.TestScheduler

class StorePermissionsTest {
    private val directory = Files.createTempDirectory("permissions-store").toFile()

    @Before fun setUp() { Persister.init(directory) }
    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun ownerAdministratorAndEveryonePermissions() {
        val everyone = role(1, Permission.VIEW_CHANNEL)
        val admin = role(2, Permission.ADMINISTRATOR)
        val roles = mapOf(1L to everyone, 2L to admin)

        assertEquals(Permission.ALL, PermissionUtils.computeGuildPermissions(9, 1, 9, null, roles))
        assertEquals(Permission.ALL, PermissionUtils.computeGuildPermissions(8, 1, 9, member(8, 2), roles))
        assertEquals(Permission.VIEW_CHANNEL, PermissionUtils.computeGuildPermissions(7, 1, 9, member(7), roles))
        assertEquals(Permission.NONE, PermissionUtils.computeGuildPermissions(7, 3, 9, member(7), roles))
    }

    @Test fun overwritesAggregateRolesThenApplyMemberLastAndHonorTypes() {
        val roles = mapOf(
            1L to role(1, Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES),
            2L to role(2, 0),
            3L to role(3, 0),
        )
        val channel = channel(
            overwrites = listOf(
                overwrite(1, PermissionOverwrite.Type.ROLE, deny = Permission.SEND_MESSAGES),
                overwrite(2, PermissionOverwrite.Type.ROLE, deny = Permission.VIEW_CHANNEL),
                overwrite(3, PermissionOverwrite.Type.ROLE, allow = Permission.SEND_MESSAGES),
                overwrite(7, PermissionOverwrite.Type.ROLE, allow = Permission.CONNECT),
                overwrite(7, PermissionOverwrite.Type.MEMBER, allow = Permission.VIEW_CHANNEL, deny = Permission.SEND_MESSAGES),
            ),
        )

        val permissions = PermissionUtils.computeChannelPermissions(7, 1, 9, member(7, 2, 3), roles, channel)

        assertTrue(PermissionUtils.can(Permission.VIEW_CHANNEL, permissions))
        assertFalse(PermissionUtils.can(Permission.SEND_MESSAGES, permissions))
        assertFalse(PermissionUtils.can(Permission.CONNECT, permissions))
    }

    @Test fun voiceAccessRequiresViewAndConnectWhilePrivateAlwaysAllows() {
        val voice = channel(type = Channel.GUILD_VOICE)
        assertFalse(PermissionUtils.hasAccess(voice, Permission.VIEW_CHANNEL))
        assertTrue(PermissionUtils.hasAccess(voice, Permission.VIEW_CHANNEL or Permission.CONNECT))
        assertTrue(PermissionUtils.hasAccess(channel(type = Channel.DM), Permission.NONE))
    }

    @Test fun storeSnapshotsGuildAndChannelPermissionsIntoExactCaches() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck, isAuthenticated = { true })
        val guildCache = Persister<Map<Long, Long>>(StorePermissions.STORE_GUILD_PERMISSIONS_V5, emptyMap())
        val channelCache = Persister<Map<Long, Map<Long, Long>>>(
            StorePermissions.STORE_CHANNEL_PERMISSIONS_BY_GUILD_V5, emptyMap(),
        )
        val permissions = StorePermissions(users, channels, guilds, deck, guildCache, channelCache)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(users, guilds, channels, permissions), deck))
        val payload = gson.fromJson(
            """{"user":{"id":7,"username":"me","discriminator":"0000"},"guilds":[{"id":1,"name":"Guild","owner_id":9,"unavailable":false,"roles":[{"id":1,"name":"@everyone","permissions":3072,"position":0,"color":0,"hoist":false,"managed":false,"mentionable":false}],"members":[{"guild_id":1,"user":{"id":7,"username":"me","discriminator":"0000"},"roles":[],"pending":false}],"channels":[{"id":10,"guild_id":1,"type":0,"name":"general"}],"features":[]}],"private_channels":[]}""",
            ModelPayload::class.java,
        )
        var guildCount = 0
        var meId = 0L
        var computed = emptyMap<Long, Long>()

        dispatcher.schedule {
            users.handleConnectionOpen(payload)
            guilds.handleConnectionOpen(payload)
            channels.handleConnectionOpen(payload)
            permissions.handleConnectionOpen()
            permissions.snapshotData()
            guildCount = guilds.getGuildsInternal().size
            meId = users.getMeInternal().id
            computed = permissions.getPermissionsByChannelInternal(1)
        }
        scheduler.triggerActions()

        assertEquals(1, guildCount)
        assertEquals(7L, meId)
        assertEquals(3072L, computed[10])
        assertEquals(3072L, permissions.getGuildPermissions()[1])
        assertEquals(3072L, permissions.getPermissionsByChannel()[10])
        assertEquals(mapOf(1L to 3072L), guildCache.get())
        assertEquals(mapOf(1L to mapOf(10L to 3072L)), channelCache.get())
    }

    private fun role(id: Long, permissions: Long) =
        GuildRole(0, "role", id, 0, false, permissions, false, false, null, null, null)

    private fun member(userId: Long, vararg roles: Long) =
        GuildMember(0, 0, roles.toList(), null, null, false, null, 1, userId)

    private fun overwrite(
        id: Long,
        type: PermissionOverwrite.Type,
        allow: Long = 0,
        deny: Long = 0,
    ) = PermissionOverwrite(id, type, allow, deny)

    private fun channel(
        type: Int = Channel.GUILD_TEXT,
        overwrites: List<PermissionOverwrite> = emptyList(),
    ) = gson.fromJson(
        """{"id":10,"guild_id":1,"type":$type,"permission_overwrites":${gson.toJson(overwrites)}}""",
        Channel::class.java,
    )

    companion object {
        private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters().create()
    }
}
