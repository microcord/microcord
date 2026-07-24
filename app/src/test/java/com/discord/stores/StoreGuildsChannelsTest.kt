package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.guild.Guild
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.schedulers.Schedulers

class StoreGuildsChannelsTest {
    private val directory = Files.createTempDirectory("guild-channel-stores").toFile()
    private val dispatcher = Dispatcher(Schedulers.from(Executor { command -> Thread(command).apply { start(); join() } }))
    private val deck = ObservationDeck()
    private val users by lazy { StoreUser(dispatcher = dispatcher, observationDeck = deck) }
    private val guilds by lazy { StoreGuilds(users, dispatcher, deck) }
    private val channels by lazy {
        StoreChannels(dispatcher, deck, { Observable.just(mapOf(10L to 1024L)) }, isAuthenticated = { true })
    }

    @Before fun setUp() {
        Persister.init(directory)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(users, guilds, channels), deck))
        dispatcher.schedule { users.handleConnectionOpen(userPayload()) }
    }

    @After fun tearDown() { directory.deleteRecursively() }

    @Test fun readyHydratesEveryIndexAndPublishesStableSnapshots() {
        val payload = ModelPayload().withGuilds(listOf(readyGuild()))
        payload.privateChannels = listOf(channel(20, Channel.DM, 0, "dm"))

        dispatcher.schedule {
            guilds.handleConnectionOpen(payload)
            channels.handleConnectionOpen(payload)
        }
        val guildSnapshot = guilds.getGuilds()
        val channelSnapshot = channels.getChannelsForGuild(1)

        assertEquals("Guild", guilds.getGuild(1)?.name)
        assertEquals(setOf(5L), guilds.getRoles()[1]?.keys)
        assertEquals("member", guilds.getMember(1, 7)?.nick)
        assertEquals(1_640_995_200_000L, guilds.getGuildsJoinedAt()[1])
        assertEquals(setOf(10L), channelSnapshot.keys)
        assertEquals(1L, channels.getChannel(11)?.guildId)
        assertEquals("thread", channels.getChannelNames()[11])
        assertEquals(setOf(20L), channels.getChannelsForGuild(0).keys)
        assertTrue(channels.getInitializedForAuthedUser())

        dispatcher.schedule { channels.handleChannelOrThreadCreateOrUpdate(channel(12, Channel.GUILD_TEXT, 1, "new")) }
        assertEquals(setOf(10L), channelSnapshot.keys)
        assertNotSame(channelSnapshot, channels.getChannelsForGuild(1))
        assertEquals(setOf(1L), guildSnapshot.keys)
    }

    @Test fun unavailableDeleteThreadEventsAndResetMatchReferenceSemantics() {
        val available = readyGuild()
        dispatcher.schedule {
            guilds.handleGuildAdd(available)
            channels.handleGuildAdd(available)
            guilds.handleGuildRemove(unavailableGuild())
            channels.handleChannelOrThreadDelete(channel(11, Channel.PUBLIC_THREAD, 1, "thread", 10))
        }

        assertEquals("Guild", guilds.getGuild(1)?.name)
        assertEquals(setOf(1L), guilds.getUnavailableGuilds())
        assertNull(channels.getChannel(11))

        dispatcher.schedule {
            guilds.reset()
            channels.reset()
        }
        assertTrue(guilds.getGuilds().isEmpty())
        assertTrue(channels.getChannelsForGuild(0).isEmpty())
        assertFalse(channels.getInitializedForAuthedUser())
    }

    @Test fun exactCacheVersionsRestoreGuildsRolesJoinedAtAndChannels() {
        dispatcher.schedule {
            guilds.handleGuildAdd(readyGuild())
            channels.handleGuildAdd(readyGuild())
        }
        Persister.persistAll()

        Persister.init(directory)
        val restoredUsers = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val restoredGuilds = StoreGuilds(restoredUsers, dispatcher, deck)
        val restoredChannels = StoreChannels(dispatcher, deck, isAuthenticated = { true })
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(restoredUsers, restoredGuilds, restoredChannels), deck))
        dispatcher.schedule {
            restoredUsers.handleConnectionOpen(userPayload())
            restoredGuilds.restore()
            restoredChannels.init()
        }

        assertEquals("Guild", restoredGuilds.getGuild(1)?.name)
        assertEquals(setOf(5L), restoredGuilds.getRoles()[1]?.keys)
        assertEquals(1_640_995_200_000L, restoredGuilds.getGuildsJoinedAt()[1])
        assertEquals(setOf(10L), restoredChannels.getChannelsForGuild(1).keys)
        assertEquals("STORE_CHANNELS_V26", StoreChannels.CHANNELS_CACHE_KEY)
    }

    private fun readyGuild() = gson.fromJson(
        """{"id":1,"name":"Guild","owner_id":7,"unavailable":false,"roles":[{"id":5,"name":"role","color":0,"hoist":false,"position":1,"permissions":0,"managed":false,"mentionable":false}],"emojis":[],"stickers":[],"features":[],"members":[{"guild_id":1,"user":{"id":7,"username":"me"},"roles":[5],"nick":"member","joined_at":"2022-01-01T00:00:00","pending":false}],"channels":[{"id":10,"type":0,"guild_id":1,"name":"general"}],"threads":[{"id":11,"type":11,"guild_id":1,"name":"thread","parent_id":10}]}""",
        Guild::class.java,
    )

    private fun unavailableGuild() = gson.fromJson(
        """{"id":1,"name":"Guild","owner_id":7,"unavailable":true,"features":[]}""",
        Guild::class.java,
    )

    private fun userPayload() = gson.fromJson(
        """{"user":{"id":7,"username":"me","discriminator":"0000"},"guilds":[],"private_channels":[]}""",
        ModelPayload::class.java,
    )

    private fun channel(id: Long, type: Int, guildId: Long, name: String, parentId: Long = 0) = gson.fromJson(
        """{"id":$id,"type":$type,"guild_id":$guildId,"name":"$name","parent_id":$parentId}""",
        Channel::class.java,
    )

    companion object {
        private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters().create()
    }
}
