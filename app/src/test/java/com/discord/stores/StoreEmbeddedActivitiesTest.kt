package com.discord.stores

import com.discord.api.activity.ActivityType
import com.discord.api.embeddedactivities.EmbeddedActivity
import com.discord.api.embeddedactivities.EmbeddedActivityInboundUpdate
import com.discord.api.guild.Guild
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreEmbeddedActivitiesTest {
    @Test
    fun readyGuildCreateAndInboundUpdatesPublishIsolatedSnapshots() {
        val deck = ObservationDeck()
        val dispatcher = Dispatcher(storeTestScheduler("embedded-activities-test"))
        val store = StoreEmbeddedActivities(deck)
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        var observedApplicationIds = emptySet<Long>()
        store.observeEmbeddedActivitiesForChannel(20L).subscribe { observedApplicationIds = it.keys }

        dispatcher.schedule { store.handleConnectionOpen(payload(GUILD_ONE)) }

        val readySnapshot = store.getEmbeddedActivities()
        val readyActivity = readySnapshot.getValue(20L).getValue(10L)
        assertEquals(listOf(2L), readyActivity.userIds)
        assertEquals(1L, readyActivity.guildId)
        assertEquals("https://10.discordsays.com", readyActivity.url)
        assertEquals(setOf(10L), observedApplicationIds)

        dispatcher.schedule { store.handleGuildCreate(guild(GUILD_TWO)) }
        assertEquals(11L, store.getEmbeddedActivities().getValue(21L).keys.single())

        dispatcher.schedule {
            store.handleEmbeddedActivityInboundUpdate(
                EmbeddedActivityInboundUpdate(20L, activity(10L), 1L, emptyList()),
            )
        }

        assertTrue(store.getEmbeddedActivities().getValue(20L).isEmpty())
        assertTrue(observedApplicationIds.isEmpty())
        assertEquals(10L, readySnapshot.getValue(20L).keys.single())

        dispatcher.schedule { store.handlePreLogout() }
        assertTrue(store.getEmbeddedActivities().isEmpty())
    }

    private fun payload(guildJson: String): ModelPayload = gson.fromJson(
        "{\"guilds\":[$guildJson]}",
        ModelPayload::class.java,
    )

    private fun guild(json: String): Guild = gson.fromJson(json, Guild::class.java)

    private fun activity(applicationId: Long) = EmbeddedActivity(
        applicationId = applicationId,
        assets = null,
        createdAt = 1L,
        details = "details",
        name = "activity",
        secrets = null,
        state = "state",
        timestamps = null,
        type = ActivityType.PLAYING,
    )

    private companion object {
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .create()

        const val GUILD_ONE = """
            {"id":1,"embedded_activities":[{"channel_id":20,"embedded_activity":{"application_id":10,"type":0},"users":[2]}]}
        """
        const val GUILD_TWO = """
            {"id":2,"embedded_activities":[{"channel_id":21,"embedded_activity":{"application_id":11,"type":0},"users":[3]}]}
        """
    }
}
