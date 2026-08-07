package com.discord.gateway

import com.discord.api.soundboard.SoundboardSounds
import com.discord.gateway.io.IncomingParser
import com.discord.gateway.io.Outgoing
import com.discord.gateway.io.SoundboardSoundsRequest
import com.discord.gateway.opcodes.Opcode
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

class SoundboardGatewayContractTest {
    @Test
    fun `requests and parses guild soundboard sounds`() {
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        assertEquals(
            """{"op":31,"d":{"guild_ids":[1,2]}}""",
            gson.toJson(Outgoing(Opcode.REQUEST_SOUNDBOARD_SOUNDS, SoundboardSoundsRequest(listOf(1L, 2L)))),
        )

        val payload = IncomingParser(gson).parse(
            """{"op":0,"t":"SOUNDBOARD_SOUNDS","d":{"guild_id":"1","soundboard_sounds":[{"sound_id":"2","name":"Airhorn","available":true}]}}""",
        ).data as SoundboardSounds
        assertEquals(1L, payload.guildId)
        assertEquals(2L, payload.soundboardSounds.single().soundId)
    }
}
