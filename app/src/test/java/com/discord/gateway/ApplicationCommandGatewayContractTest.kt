package com.discord.gateway

import com.discord.api.commands.GuildApplicationCommandIndexUpdate
import com.discord.api.commands.ApplicationCommandAutocompleteResult
import com.discord.gateway.io.IncomingParser
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationCommandGatewayContractTest {
    private val parser = IncomingParser(
        GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters().create(),
    )

    @Test
    fun commandIndexUpdateIsTypedForCacheInvalidation() {
        val incoming = parser.parse(
            """{"op":0,"t":"GUILD_APPLICATION_COMMAND_INDEX_UPDATE","s":9,"d":{"guild_id":"42"}}""",
        )

        assertEquals(42L, (incoming.data as GuildApplicationCommandIndexUpdate).guildId)
    }

    @Test
    fun autocompleteResponseKeepsNonceLocalizedNamesAndNumericValues() {
        val incoming = parser.parse(
            """{"op":0,"t":"APPLICATION_COMMAND_AUTOCOMPLETE_RESPONSE","s":10,"d":{"nonce":"fixture-nonce","choices":[{"name":"Two","name_localized":"Zwei","value":2},{"name":"Half","value":2.5}]}}""",
        )

        val result = incoming.data as ApplicationCommandAutocompleteResult
        assertEquals("fixture-nonce", result.nonce)
        assertEquals("Zwei", result.choices.first().displayName)
        assertEquals(2.0, result.choices.first().value)
        assertEquals(2.5, result.choices.last().value)
    }
}
