package com.discord.widgets.channels.memberlist

import com.discord.api.user.Collectibles
import com.discord.api.user.Nameplate
import com.discord.api.user.UserProfile
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameplateRowsTest {
    @Test
    fun payloadAndAssetContractsMatchDesktopRenderer() {
        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        val json = """{"id":"2","username":"target","discriminator":"0","collectibles":{"nameplate":{"asset":"ignored-by-renderer","sku_id":"1344802364934062152","label":"Angel nameplate","palette":"bubble_gum","expires_at":null}}}"""
        val user = gson.fromJson(json, com.discord.api.user.User::class.java)
        val profile = gson.fromJson("""{"user":$json}""", UserProfile::class.java)
        val plate = requireNotNull(user.collectibles?.nameplate)

        assertEquals(user.collectibles, CoreUser(user).collectibles)
        assertEquals(user.collectibles, profile.user.collectibles)
        assertEquals(
            "https://cdn.discordapp.com/media/v1/collectibles-shop/1344802364934062152/static",
            plate.staticImageUrl(),
        )
        assertEquals(
            listOf(
                "https://cdn.discordapp.com/media/v1/collectibles-shop/1344802364934062152/animated",
                "https://cdn.discordapp.com/media/v1/collectibles-shop/1344802364934062152/static",
            ),
            plate.imageUrls(animate = true),
        )
        assertEquals(listOf(plate.staticImageUrl()), plate.imageUrls(animate = false))
    }

    @Test
    fun invalidOrExpiredPayloadsFailSafely() {
        val plate = nameplate(expiresAt = 101)

        assertTrue(plate.isActive(100))
        assertFalse(plate.isActive(101))
        assertEquals("#DC3E97", plate.backgroundColorHex(dark = true))
        assertEquals("#F957B3", plate.backgroundColorHex(dark = false))
        assertEquals("#000000", plate.copy(palette = "black").backgroundColorHex(dark = true))
        assertNull(plate.copy(palette = "future_palette").backgroundColorHex(dark = true))
        assertNull(plate.copy(skuId = 0).staticImageUrl())
        assertTrue(plate.copy(skuId = 0).imageUrls(animate = true).isEmpty())
    }

    @Test
    fun memberRowsPreferGuildNameplateAndFallBackToUserNameplate() {
        val global = nameplate(skuId = 1)
        val guild = nameplate(skuId = 2)
        val user = CoreUser(2, "target", collectibles = Collectibles(global))
        val member = GuildMember().copy(guildId = 1, userId = 2, collectibles = Collectibles(guild))

        val guildItem = row(user, member).toAdapterItem() as ChannelMembersListAdapter.Item.Member
        val globalItem = row(user, null).toAdapterItem() as ChannelMembersListAdapter.Item.Member

        assertEquals(guild, guildItem.nameplate)
        assertEquals(global, globalItem.nameplate)
    }

    private fun row(user: CoreUser, member: GuildMember?) = ChannelMemberListRow.Member(
        user = user,
        guildMember = member,
        presence = null,
        displayName = user.username,
        isOwner = false,
    )

    private fun nameplate(skuId: Long = 1344802364934062152, expiresAt: Long? = null) = Nameplate(
        asset = "legacy-asset",
        skuId = skuId,
        label = "Angel nameplate",
        palette = "bubble_gum",
        expiresAt = expiresAt,
    )
}
