package com.discord.stores

import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.persister.Persister
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import rx.schedulers.TestScheduler

class ReadyPayloadHydratorTest {
    @Test
    fun compactReadyHydratesRelationshipsDmsMembersAndPresences() {
        val (guilds, channels) = stores()
        val payload = gson.fromJson(COMPACT_READY, ModelPayload::class.java)

        val hydrated = ReadyPayloadHydrator(guilds, channels).hydrate(payload)

        assertEquals("friend", hydrated.relationships?.single()?.user?.username)
        assertEquals("friend", hydrated.privateChannels?.single()?.recipients?.single()?.username)
        assertEquals("me", hydrated.guilds?.single()?.members?.single()?.user?.username)
        assertEquals(1L, hydrated.guilds?.single()?.members?.single()?.guildId)
        assertNotNull(hydrated.guilds?.single()?.presences?.single()?.user)
    }

    @Test
    fun omittedGuildWithoutCacheReturnsError() {
        val (guilds, channels) = stores()
        val payload = gson.fromJson(OMITTED_READY, ModelPayload::class.java)

        val result = ReadyPayloadHydrator(guilds, channels).hydrateReadyPayload(payload)

        assertSame(ReadyPayloadHydrator.HydrateResult.Error, result)
    }

    @Test
    fun omittedGuildCarriesCachedMetadataAndMergesOnlyNonzeroLastMessageIds() {
        val (guilds, channels) = stores()
        val cachedPayload = gson.fromJson(CACHED_READY, ModelPayload::class.java)
        guilds.handleConnectionOpen(cachedPayload)
        channels.handleConnectionOpen(cachedPayload)
        val incoming = gson.fromJson(OMITTED_READY, ModelPayload::class.java)

        val result = ReadyPayloadHydrator(guilds, channels).hydrateReadyPayload(incoming)
            as ReadyPayloadHydrator.HydrateResult.Success
        val cached = cachedPayload.guilds!!.single()
        val hydrated = result.payload.guilds!!.single()

        assertEquals(scalars(cached), scalars(hydrated))
        assertEquals(7, hydrated.premiumSubscriptionCount)
        assertEquals(8, hydrated.memberCount)
        assertEquals(listOf(99L, 123L), hydrated.channels!!.map { it.lastMessageId })
        assertNull(hydrated.channelUpdates)
        assertEquals(cached.roles, hydrated.roles)
    }

    private fun stores(): Pair<StoreGuilds, StoreChannels> {
        Persister.init(Files.createTempDirectory("ready-hydrator").toFile())
        val dispatcher = Dispatcher(TestScheduler())
        val deck = ObservationDeck()
        return StoreGuilds(StoreUser(dispatcher = dispatcher, observationDeck = deck), dispatcher, deck) to
            StoreChannels(dispatcher, deck)
    }

    private fun scalars(guild: com.discord.api.guild.Guild) = listOf(
        guild.name,
        guild.description,
        guild.defaultMessageNotifications,
        guild.region,
        guild.ownerId,
        guild.icon,
        guild.verificationLevel,
        guild.explicitContentFilter,
        guild.mfaLevel,
        guild.afkTimeout,
        guild.afkChannelId,
        guild.systemChannelId,
        guild.features,
        guild.banner,
        guild.splash,
        guild.premiumTier,
        guild.systemChannelFlags,
        guild.rulesChannelId,
        guild.publicUpdatesChannelId,
        guild.preferredLocale,
        guild.maxVideoChannelUsers,
        guild.vanityUrlCode,
    )

    companion object {
        private val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters().create()
        private val COMPACT_READY = """
            {
              "user":{"id":7,"username":"me","discriminator":"0000"},
              "users":[{"id":8,"username":"friend","discriminator":"0001"}],
              "relationships":[{"id":8,"user_id":8,"type":1}],
              "private_channels":[{"id":20,"type":1,"recipient_ids":[8]}],
              "guilds":[{"id":1,"name":"Guild","owner_id":9,"roles":[{"id":1,"name":"@everyone","permissions":1024}],"channels":[{"id":10,"guild_id":1,"type":0,"name":"general"}],"features":[]}],
              "merged_members":[[{"guild_id":1,"user_id":7,"roles":[1],"pending":false}]],
              "merged_presences":{"friends":[],"guilds":[[{"user_id":8,"status":"online"}]]}
            }
        """.trimIndent()
        private val CACHED_READY = """
            {
              "users":[],
              "guilds":[{
                "id":1,"name":"cached-name","description":"cached-description",
                "default_message_notifications":2,"region":"cached-region","owner_id":42,
                "icon":"cached-icon","verification_level":3,"explicit_content_filter":2,
                "mfa_level":1,"afk_timeout":300,"afk_channel_id":101,"system_channel_id":102,
                "features":["BANNER","COMMUNITY"],"member_count":70,"banner":"cached-banner",
                "splash":"cached-splash","premium_tier":3,"premium_subscription_count":77,
                "system_channel_flags":12,"rules_channel_id":103,"public_updates_channel_id":104,
                "preferred_locale":"tr","max_video_channel_users":25,"vanity_url_code":"cached-vanity",
                "roles":[{"id":1,"name":"@everyone","permissions":1024}],
                "channels":[
                  {"id":10,"guild_id":1,"type":0,"name":"one","last_message_id":99},
                  {"id":11,"guild_id":1,"type":0,"name":"two","last_message_id":100}
                ]
              }]
            }
        """.trimIndent()
        private val OMITTED_READY = """
            {
              "users":[],
              "guilds":[{
                "id":1,"name":"incoming-name","description":"incoming-description",
                "default_message_notifications":0,"region":"incoming-region","owner_id":5,
                "icon":"incoming-icon","verification_level":0,"explicit_content_filter":0,
                "mfa_level":0,"afk_timeout":60,"afk_channel_id":201,"system_channel_id":202,
                "features":[],"member_count":8,"banner":"incoming-banner","splash":"incoming-splash",
                "premium_tier":0,"premium_subscription_count":7,"system_channel_flags":0,
                "rules_channel_id":203,"public_updates_channel_id":204,"preferred_locale":"en-US",
                "max_video_channel_users":5,"vanity_url_code":"incoming-vanity","roles":[],
                "guild_hashes":{
                  "version":1,
                  "metadata":{"hash":"m","omitted":true},
                  "channels":{"hash":"c","omitted":true},
                  "roles":{"hash":"r","omitted":true}
                },
                "channel_updates":[
                  {"id":10,"guild_id":1,"type":0,"last_message_id":0},
                  {"id":11,"guild_id":1,"type":0,"last_message_id":123}
                ]
              }]
            }
        """.trimIndent()
    }
}
