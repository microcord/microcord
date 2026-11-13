package com.discord.stores

import com.discord.api.emoji.GuildEmoji
import com.discord.api.guild.GuildExplicitContentFilter
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildHubType
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guild.GuildNsfwLevel
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.role.GuildRole
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerType
import com.discord.models.guild.Guild
import com.discord.utilities.persister.Persister
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import rx.Observable

class CachedGuildCachesTest {
    private val directory = Files.createTempDirectory("cached-guilds").toFile()

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun guildsRoundTripEveryFieldAndReadTheOldPlainMapFormat() {
        // Gson hands the stores ArrayLists/HashSets; Kryo cannot rebuild Kotlin's `listOf`.
        val guilds = mapOf(1L to populatedGuild(1L), 2L to Guild(name = "bare", id = 2L))

        Persister.init(directory, Observable.never())
        Persister("old", emptyMap<Long, Guild>()).set(HashMap(guilds), persist = true)
        Persister("new", emptyMap<Long, Guild>()).set(CachedGuildMap().apply { putAll(guilds) }, persist = true)

        Persister.init(directory, Observable.never())
        // Kryo's reflective format rebuilds the `Unlimited` object as a second instance, so the old
        // file only matches once that singleton is put back; the new format restores it directly.
        val old = Persister("old", emptyMap<Long, Guild>()).get().mapValues { (_, guild) ->
            if (guild.maxVideoChannelUsers is GuildMaxVideoChannelUsers.Limited) guild
            else guild.copy(maxVideoChannelUsers = GuildMaxVideoChannelUsers.Unlimited)
        }
        assertEquals(guilds, old)
        val restored = Persister("new", emptyMap<Long, Guild>()).get()
        assertEquals(guilds, restored)
        assertTrue(restored is CachedGuildMap)
        assertTrue(restored.getValue(2L).maxVideoChannelUsers === GuildMaxVideoChannelUsers.Unlimited)
        assertEquals(guilds.getValue(1L).shortName, restored.getValue(1L).shortName)
    }

    @Test
    fun rolesRoundTripEveryFieldAndReadTheOldPlainMapFormat() {
        val roles = mapOf(1L to hashMapOf(10L to role(10L), 11L to role(11L).copy(icon = null, unicodeEmoji = null)))

        Persister.init(directory, Observable.never())
        Persister("old", emptyMap<Long, Map<Long, GuildRole>>()).set(HashMap(roles), persist = true)
        Persister("new", emptyMap<Long, Map<Long, GuildRole>>()).set(CachedGuildRoles().apply { putAll(roles) }, persist = true)

        Persister.init(directory, Observable.never())
        assertEquals(roles, Persister("old", emptyMap<Long, Map<Long, GuildRole>>()).get())
        val restored = Persister("new", emptyMap<Long, Map<Long, GuildRole>>()).get()
        assertEquals(roles, restored)
        assertTrue(restored is CachedGuildRoles)
    }

    private fun role(id: Long) = GuildRole(
        position = 3, name = "role $id", id = id, color = 0xFF00FF, hoist = true, permissions = 1L shl 40,
        mentionable = true, managed = true, icon = "icon", tags = null, unicodeEmoji = "x",
    )

    @Suppress("UNCHECKED_CAST")
    private fun populatedGuild(id: Long) = Guild(
        roles = arrayListOf(role(5L)),
        emojis = arrayListOf(
            GuildEmoji(id = 20L, name = "e", roles = arrayListOf(5L), requireColons = true, managed = true, animated = true, available = false),
            GuildEmoji(id = 21L, name = "f", roles = null, requireColons = false, managed = false, animated = false, available = null),
        ),
        stickers = arrayListOf(
            Sticker(
                id = 30L, packId = 31L, guildId = id, name = "s", description = "d",
                formatType = StickerFormatType.APNG, tags = "t", type = StickerType.GUILD, available = true,
            ),
        ),
        name = "Populated's guild",
        description = "description",
        defaultMessageNotifications = 1,
        id = id,
        region = "region",
        ownerId = 40L,
        icon = "icon",
        verificationLevel = GuildVerificationLevel.HIGH,
        explicitContentFilter = GuildExplicitContentFilter.values().last(),
        unavailable = true,
        mfaLevel = 1,
        afkTimeout = 300,
        afkChannelId = 41L,
        systemChannelId = 42L,
        // Gson leaves features this build does not know as null entries.
        features = hashSetOf(GuildFeature.VANITY_URL, GuildFeature.VERIFIED, null) as Set<GuildFeature>,
        memberCount = 1000,
        banner = "banner",
        splash = "splash",
        premiumTier = 2,
        premiumSubscriptionCount = 14,
        systemChannelFlags = 3,
        joinedAt = "2020-01-01",
        rulesChannelId = 43L,
        publicUpdatesChannelId = 44L,
        preferredLocale = "tr",
        maxVideoChannelUsers = GuildMaxVideoChannelUsers.Limited(25),
        vanityUrlCode = "vanity",
        approximatePresenceCount = 50,
        nsfw = true,
        hubType = GuildHubType.COLLEGE,
        safetyAlertsChannelId = 45L,
        premiumProgressBarEnabled = true,
        nsfwLevel = GuildNsfwLevel.AGE_RESTRICTED,
        ownerConfiguredContentLevel = GuildNsfwLevel.SAFE,
        officialMessageColor = 0x123456,
    )
}
