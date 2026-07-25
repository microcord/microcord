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
import org.junit.Test
import rx.schedulers.TestScheduler

class ReadyPayloadHydratorTest {
    @Test
    fun compactReadyHydratesRelationshipsDmsMembersAndPresences() {
        Persister.init(Files.createTempDirectory("ready-hydrator").toFile())
        val dispatcher = Dispatcher(TestScheduler())
        val deck = ObservationDeck()
        val users = StoreUser(dispatcher = dispatcher, observationDeck = deck)
        val guilds = StoreGuilds(users, dispatcher, deck)
        val channels = StoreChannels(dispatcher, deck)
        val payload = gson.fromJson(COMPACT_READY, ModelPayload::class.java)

        val hydrated = ReadyPayloadHydrator(guilds, channels).hydrate(payload)

        assertEquals("friend", hydrated.relationships?.single()?.user?.username)
        assertEquals("friend", hydrated.privateChannels?.single()?.recipients?.single()?.username)
        assertEquals("me", hydrated.guilds?.single()?.members?.single()?.user?.username)
        assertEquals(1L, hydrated.guilds?.single()?.members?.single()?.guildId)
        assertNotNull(hydrated.guilds?.single()?.presences?.single()?.user)
    }

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
    }
}
