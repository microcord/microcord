package com.discord.widgets.stickers

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.premium.PremiumTier
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerType
import com.discord.api.user.NsfwAllowance
import com.discord.models.user.MeUser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sendability decides both whether a tap sends and whether a picker tile is dimmed, so every branch
 * matters: a wrong answer either greys out a usable sticker or lets a send fail server-side.
 */
class StickerSendabilityTest {

    @Test
    fun `standard stickers need nitro`() {
        assertEquals(
            StickerSendability.SENDABLE_WITH_PREMIUM,
            getStickerSendability(standard, user(PremiumTier.NONE)),
        )
        assertEquals(
            StickerSendability.SENDABLE,
            getStickerSendability(standard, user(PremiumTier.TIER_2)),
        )
        // TIER_0 is the grandfathered tier that also unlocks the premium packs.
        assertEquals(
            StickerSendability.SENDABLE,
            getStickerSendability(standard, user(PremiumTier.TIER_0)),
        )
        // Nitro Classic does not.
        assertEquals(
            StickerSendability.SENDABLE_WITH_PREMIUM,
            getStickerSendability(standard, user(PremiumTier.TIER_1)),
        )
    }

    @Test
    fun `a guild sticker is free inside its own guild regardless of nitro`() {
        assertEquals(
            StickerSendability.SENDABLE,
            getStickerSendability(guildSticker, user(PremiumTier.NONE), guildChannel(guildId = 7L)),
        )
    }

    @Test
    fun `a foreign guild sticker needs both nitro and the external permission`() {
        val elsewhere = guildChannel(guildId = 99L)
        assertEquals(
            StickerSendability.NONSENDABLE,
            getStickerSendability(guildSticker, user(PremiumTier.TIER_2), elsewhere, 0L),
        )
        assertEquals(
            StickerSendability.SENDABLE_WITH_PREMIUM,
            getStickerSendability(
                guildSticker,
                user(PremiumTier.NONE),
                elsewhere,
                Permission.USE_EXTERNAL_STICKERS,
            ),
        )
        assertEquals(
            StickerSendability.SENDABLE,
            getStickerSendability(
                guildSticker,
                user(PremiumTier.TIER_2),
                elsewhere,
                Permission.USE_EXTERNAL_STICKERS,
            ),
        )
    }

    @Test
    fun `DMs have no external-sticker permission to withhold`() {
        assertEquals(
            StickerSendability.SENDABLE,
            getStickerSendability(
                guildSticker,
                user(PremiumTier.TIER_2),
                dmChannel(),
                currentChannelPermissions = 0L,
            ),
        )
    }

    @Test
    fun `a sticker whose guild lost its boost level is inert`() {
        assertEquals(
            StickerSendability.SENDABLE_WITH_PREMIUM_GUILD,
            getStickerSendability(
                guildSticker.copy(available = false),
                user(PremiumTier.TIER_2),
                guildChannel(guildId = 7L),
            ),
        )
    }

    @Test
    fun `an unknown sticker type can never be sent`() {
        assertEquals(
            StickerSendability.NONSENDABLE,
            getStickerSendability(standard.copy(type = StickerType.UNKNOWN), user(PremiumTier.TIER_2)),
        )
    }

    private val standard = Sticker(
        id = 1L,
        packId = 100L,
        guildId = null,
        name = "wumpus",
        description = "",
        formatType = StickerFormatType.PNG,
        tags = "wave",
        type = StickerType.STANDARD,
    )

    private val guildSticker = standard.copy(
        id = 2L,
        packId = null,
        guildId = 7L,
        type = StickerType.GUILD,
        available = true,
    )

    private fun user(tier: PremiumTier) = MeUser(
        id = 1L, username = "me", avatar = null, banner = null, isBot = false,
        isSystemUser = false, discriminator = 1, premiumTier = tier, email = null,
        mfaEnabled = false, isVerified = true, token = null, flags = 0, publicFlags = 0,
        phoneNumber = null, nsfwAllowance = NsfwAllowance.UNKNOWN, bio = null, bannerColor = null,
    )

    private fun guildChannel(guildId: Long) = channel(type = Channel.GUILD_TEXT, guildId = guildId)

    private fun dmChannel() = channel(type = Channel.DM, guildId = 0L)

    private fun channel(type: Int, guildId: Long) = Channel(
        topic = null, type = type, guildId = guildId, name = "c", lastMessageId = 0, id = 10,
        ownerId = 0, recipientIds = null, recipients = null, position = 0,
        permissionOverwrites = null, userLimit = 0, bitrate = 0, icon = null,
        originChannelId = 0, applicationId = 0, nicks = null, nsfw = false,
        parentId = 0, memberListId = null, rateLimitPerUser = 0, defaultAutoArchiveDuration = null,
        rtcRegion = null, flags = null, guildHashes = null, availableTags = null,
        appliedTags = null, threadMetadata = null, messageCount = null,
        memberCount = null, memberIdsPreview = null, member = null,
    )
}
