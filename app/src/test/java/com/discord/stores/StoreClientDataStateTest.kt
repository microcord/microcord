package com.discord.stores

import com.discord.api.emoji.GuildEmojisUpdate
import com.discord.api.sticker.GuildStickersUpdate
import com.google.gson.Gson
import org.junit.Test

class StoreClientDataStateTest {
    @Test
    fun `partial emoji and sticker updates without guild hashes are ignored`() {
        val gson = Gson()
        val store = StoreClientDataState()

        store.handleEmojiUpdate(
            gson.fromJson("""{"guildId":1,"emojis":[]}""", GuildEmojisUpdate::class.java),
        )
        store.handleStickersUpdate(
            gson.fromJson("""{"guildId":1,"stickers":[]}""", GuildStickersUpdate::class.java),
        )
    }
}
