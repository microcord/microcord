package com.discord.api.guild

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

class GuildVerificationLevelTypeAdapter : TypeAdapter<GuildVerificationLevel>() {
    override fun read(input: JsonReader): GuildVerificationLevel {
        val value = input.nextInt()
        return GuildVerificationLevel.entries.firstOrNull { it.apiValue == value } ?: GuildVerificationLevel.NONE
    }

    override fun write(output: JsonWriter, value: GuildVerificationLevel?) {
        if (value == null) output.nullValue() else output.value(value.apiValue)
    }
}

class GuildExplicitContentFilterTypeAdapter : TypeAdapter<GuildExplicitContentFilter>() {
    override fun read(input: JsonReader): GuildExplicitContentFilter {
        val value = input.nextInt()
        return GuildExplicitContentFilter.entries.firstOrNull { it.apiValue == value } ?: GuildExplicitContentFilter.NONE
    }

    override fun write(output: JsonWriter, value: GuildExplicitContentFilter?) {
        if (value == null) output.nullValue() else output.value(value.apiValue)
    }
}

class GuildNsfwLevelTypeAdapter : TypeAdapter<GuildNsfwLevel>() {
    override fun read(input: JsonReader): GuildNsfwLevel? {
        if (input.peek() == JsonToken.NULL) return input.nextNull().let { null }
        return GuildNsfwLevel.fromApiValue(input.nextInt())
    }

    override fun write(output: JsonWriter, value: GuildNsfwLevel?) {
        if (value == null) output.nullValue() else output.value(value.apiValue)
    }
}

class GuildHubTypeTypeAdapter : TypeAdapter<GuildHubType>() {
    override fun read(input: JsonReader): GuildHubType? {
        if (input.peek() == JsonToken.NULL) return input.nextNull().let { null }
        val value = input.nextInt()
        return GuildHubType.entries.firstOrNull { it.apiValue == value }
    }

    override fun write(output: JsonWriter, value: GuildHubType?) {
        if (value == null) output.nullValue() else output.value(value.apiValue)
    }
}

class GuildMaxVideoChannelUsersTypeAdapter : TypeAdapter<GuildMaxVideoChannelUsers>() {
    override fun read(input: JsonReader): GuildMaxVideoChannelUsers =
        input.nextInt().let { if (it == -1) GuildMaxVideoChannelUsers.Unlimited else GuildMaxVideoChannelUsers.Limited(it) }

    override fun write(output: JsonWriter, value: GuildMaxVideoChannelUsers?) {
        when (value) {
            is GuildMaxVideoChannelUsers.Limited -> output.value(value.limit)
            GuildMaxVideoChannelUsers.Unlimited -> output.value(-1)
            null -> output.nullValue()
        }
    }
}
