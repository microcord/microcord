package com.discord.gateway.io

import com.discord.api.stageinstance.StageInstance
import com.discord.gateway.opcodes.Opcode
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser

class IncomingParser(private val gson: Gson) {
    fun parse(json: String): Incoming {
        val envelope = JsonParser.parseString(json).asJsonObject
        val type = envelope["t"]?.takeUnless(JsonElement::isJsonNull)?.asString
        val seq = envelope["s"]?.takeUnless(JsonElement::isJsonNull)?.asInt
        val opcode = Opcode.fromApiInt(envelope["op"]?.asInt ?: -1)
        val rawData = envelope["d"]
        val data = when {
            rawData == null || rawData.isJsonNull -> null
            opcode == Opcode.HELLO -> gson.fromJson(rawData, Hello::class.java)
            opcode == Opcode.INVALID_SESSION -> rawData.asBoolean
            opcode == Opcode.DISPATCH && type in STAGE_EVENTS -> gson.fromJson(rawData, StageInstance::class.java)
            else -> rawData.deepCopy()
        }
        return Incoming(type, seq, opcode, data)
    }

    private companion object {
        val STAGE_EVENTS = setOf("STAGE_INSTANCE_CREATE", "STAGE_INSTANCE_UPDATE", "STAGE_INSTANCE_DELETE")
    }
}
