package com.discord.stores

import com.discord.api.emoji.GuildEmojisUpdate
import com.discord.api.message.Message
import com.discord.api.sticker.GuildStickersUpdate
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.lang.reflect.Modifier
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test
import com.discord.utilities.persister.Persister

class StoreClientDataStateTest {
    @Test
    fun `entity updates retain the highest guild version`() {
        val gson = GsonBuilder().excludeFieldsWithModifiers(Modifier.STATIC).create()
        Persister.init(Files.createTempDirectory("client-state-v2").toFile())
        val store = StoreClientDataState()

        store.handleEmojiUpdate(
            gson.fromJson("""{"guildId":1,"emojis":[{"id":2,"name":"e","version":7}]}""", GuildEmojisUpdate::class.java),
        )
        store.handleStickersUpdate(
            gson.fromJson("""{"guildId":1,"stickers":[{"id":3,"name":"s","description":"","tags":"","formatType":1,"type":2,"version":9}]}""", GuildStickersUpdate::class.java),
        )
        store.snapshotData()

        assertEquals(9L, store.getClientDataState().guildVersions[1L])
    }

    @Test
    fun `connection refresh does not persist an empty intermediate state`() {
        Persister.init(Files.createTempDirectory("client-state-v2-refresh").toFile())
        val cached = StoreClientDataState.ClientDataState(guildVersions = mapOf(1L to 9L))
        Persister(StoreClientDataState.CACHE_KEY, StoreClientDataState.ClientDataState()).set(cached, persist = true)

        StoreClientDataState().clear(persist = false)

        assertEquals(
            cached,
            Persister(StoreClientDataState.CACHE_KEY, StoreClientDataState.ClientDataState()).get(),
        )
    }

    @Test
    fun `direct messages advance only the private channel version`() {
        Persister.init(Files.createTempDirectory("client-state-v2-messages").toFile())
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters().create()
        val store = StoreClientDataState()

        store.handleMessageCreate(gson.fromJson("""{"id":100,"channel_id":10,"guild_id":1}""", Message::class.java))
        store.handleMessageCreate(gson.fromJson("""{"id":200,"channel_id":20}""", Message::class.java))
        store.snapshotData()

        assertEquals(100L, store.getClientDataState().highestLastMessageId)
        assertEquals(200L, store.getClientDataState().privateChannelsVersion)
    }
}
