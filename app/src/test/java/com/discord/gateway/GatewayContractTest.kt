package com.discord.gateway

import android.content.SharedPreferences
import com.discord.api.stageinstance.StageInstance
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.gateway.io.Identify
import com.discord.gateway.io.IdentifyClientState
import com.discord.gateway.io.IncomingParser
import com.discord.gateway.io.Outgoing
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.models.domain.ModelGateway
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayContractTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .serializeNulls()
        .registerDiscordApiTypeAdapters()
        .create()

    @Test
    fun discoveryPersistsAcrossRestartAndRejectsInvalidUrls() {
        val preferences = memoryPreferences()
        var calls = 0
        assertEquals("wss://gateway.example", GatewayDiscovery(preferences) { calls++; ModelGateway("wss://gateway.example") }.discover())
        assertEquals("wss://gateway.example", GatewayDiscovery(preferences) { calls++; ModelGateway(null) }.discover())
        assertEquals(1, calls)
        assertEquals("wss://gateway.example", preferences.getString(GatewayDiscovery.GATEWAY_URL_CACHE_KEY, null))

        preferences.edit().putString(GatewayDiscovery.GATEWAY_URL_CACHE_KEY, "ws://cached.example").apply()
        assertEquals("wss://fresh.example", GatewayDiscovery(preferences) { ModelGateway("wss://fresh.example") }.discover())
        assertThrows(IllegalArgumentException::class.java) {
            GatewayDiscovery(preferences) { ModelGateway(null) }.discover(ignoreSticky = true)
        }
        assertEquals(null, preferences.getString(GatewayDiscovery.GATEWAY_URL_CACHE_KEY, null))

        listOf(null, "not a url", "ws://gateway.example", "https://gateway.example").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { GatewayDiscovery { ModelGateway(url) }.discover() }
        }
    }

    @Test
    fun controlPayloadsUseExactWireNamesAndExplicitNullData() {
        assertEquals("{\"op\":7,\"d\":null}", gson.toJson(Outgoing<Any>(7)))
        val identify = gson.toJsonTree(
            Identify("token", 100, true, 351, emptyMap(), IdentifyClientState(emptyMap(), 0, 1, 2)),
        ).asJsonObject
        assertTrue(identify.has("client_state"))
        assertTrue(identify["client_state"].asJsonObject.has("useruser_guild_settings_version"))
        val nullableState = gson.toJsonTree(Identify("token", 100, true, 351, emptyMap(), null)).asJsonObject
        assertTrue(nullableState["client_state"].isJsonNull)
    }

    @Test
    fun parserTypesOnlyStageEventsAndPreservesUnknownData() {
        val parser = IncomingParser(gson)
        val create = parser.parse(stageEvent("STAGE_INSTANCE_CREATE", "topic"))
        val update = parser.parse(stageEvent("STAGE_INSTANCE_UPDATE", "updated"))
        val delete = parser.parse(stageEvent("STAGE_INSTANCE_DELETE", "updated"))
        assertEquals(StageInstancePrivacyLevel.GUILD_ONLY, (create.data as StageInstance).privacyLevel)
        assertEquals("updated", (update.data as StageInstance).topic)
        assertEquals(3L, (delete.data as StageInstance).channelId)

        val unknown = parser.parse("{\"op\":0,\"s\":4,\"t\":\"FUTURE_EVENT\",\"d\":{\"value\":1}}")
        assertTrue(unknown.data is JsonElement)
        assertEquals(1, (unknown.data as JsonElement).asJsonObject["value"].asInt)
    }

    private fun stageEvent(type: String, topic: String) =
        "{\"op\":0,\"s\":4,\"t\":\"$type\",\"d\":{\"id\":1,\"guild_id\":2,\"channel_id\":3,\"topic\":\"$topic\",\"privacy_level\":2,\"invite_code\":null}}"

    private fun memoryPreferences(): SharedPreferences {
        val values = linkedMapOf<String, Any?>()
        lateinit var editor: Any
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> editor.also { values[args[0] as String] = args[1] }
                "remove" -> editor.also { values.remove(args[0] as String) }
                "apply" -> null
                else -> editor
            }
        }
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0]] ?: args[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }
}
