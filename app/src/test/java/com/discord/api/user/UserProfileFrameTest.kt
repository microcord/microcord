package com.discord.api.user

import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserProfileFrameTest {
    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .registerDiscordApiTypeAdapters()
        .create()

    private fun profile(json: String) = gson.fromJson(json, UserProfile::class.java)

    @Test
    fun aGuildProfileWithoutAFrameStillShowsTheGlobalOne() {
        val profile = profile(
            """{"user_profile":{"collectibles":[{"sku_id":"11","type":3}]},
                "guild_member_profile":{"guild_id":"5","bio":"hi"}}""",
        )
        assertEquals(11L, profile.equippedProfileFrameSkuId())
    }

    @Test
    fun aGuildProfileFrameWinsAndOtherCollectiblesAreIgnored() {
        val profile = profile(
            """{"user_profile":{"collectibles":[{"sku_id":"11","type":3}]},
                "guild_member_profile":{"collectibles":[{"sku_id":"7","type":1},{"sku_id":"22","type":3}]}}""",
        )
        assertEquals(22L, profile.equippedProfileFrameSkuId())
        assertNull(profile("""{"user_profile":{"collectibles":[{"sku_id":"7","type":1}]}}""").equippedProfileFrameSkuId())
    }
}
