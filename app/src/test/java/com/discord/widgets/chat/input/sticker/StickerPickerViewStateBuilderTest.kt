package com.discord.widgets.chat.input.sticker

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.premium.PremiumTier
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerType
import com.discord.api.user.NsfwAllowance
import com.discord.models.guild.Guild
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.models.user.MeUser
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's grid is assembled by hand — section order, the search filter, and the `[start, end)`
 * ranges that keep the tab rail in sync with the scroll position. Those are the parts that fail
 * quietly, so they are pinned here rather than left to on-device inspection.
 */
class StickerPickerViewStateBuilderTest {

    @Test
    fun `sections are recents then guilds then packs, each behind its own header`() {
        val state = state(
            frequentlyUsedStickerIds = listOf(GUILD_STICKER_A.id),
            meUser = user(PremiumTier.TIER_2),
        )

        val viewState = build(state) as StickerPickerViewModel.ViewState.Stickers

        assertEquals(
            listOf(
                "recents",
                "sticker:${GUILD_STICKER_A.id}",
                "${GUILD.id}",
                "sticker:${GUILD_STICKER_A.id}",
                "sticker:${GUILD_STICKER_B.id}",
                "owned-header:${PACK.id}",
                "sticker:${PACK_STICKER.id}",
            ),
            viewState.stickerItems.map { it.key },
        )
    }

    @Test
    fun `standard packs are hidden from a user who cannot send them`() {
        val viewState = build(state(meUser = user(PremiumTier.NONE)))
            as StickerPickerViewModel.ViewState.Stickers

        assertTrue(
            viewState.stickerItems.none {
                it is HeaderItem && it.headerType is HeaderType.PackItem
            },
        )
        assertTrue(viewState.stickerItems.none { it.key == "sticker:${PACK_STICKER.id}" })
    }

    @Test
    fun `a user with neither nitro nor guild stickers gets the nitro pitch`() {
        val promo = sticker(id = StickerPickerViewStateBuilder.EMPTY_STATE_STICKER_IDS[0])
        val viewState = build(
            state(guildStickers = emptyMap(), meUser = user(PremiumTier.NONE)),
            resolveSticker = { id -> promo.takeIf { it.id == id } },
        )

        val empty = viewState as StickerPickerViewModel.ViewState.EmptyNonPremium
        assertEquals(listOf(promo), empty.emptyStateStickers)
    }

    @Test
    fun `search matches name or tags and drops sections it empties`() {
        val state = state(searchQuery = "PARTY", meUser = user(PremiumTier.TIER_2))

        val viewState = build(state) as StickerPickerViewModel.ViewState.Stickers

        // Only GUILD_STICKER_B carries the "party" tag, so the pack section disappears entirely —
        // header included — rather than showing an empty heading.
        assertEquals(
            listOf("${GUILD.id}", "sticker:${GUILD_STICKER_B.id}"),
            viewState.stickerItems.map { it.key },
        )
    }

    @Test
    fun `a search that matches nothing is reported as an empty search, not an empty picker`() {
        val viewState = build(state(searchQuery = "zzzz", meUser = user(PremiumTier.TIER_2)))
        assertTrue(viewState is StickerPickerViewModel.ViewState.EmptySearchResults)

        // The same empty grid with no query behind it is just a picker with nothing in it yet,
        // which must not be dressed up as a failed search.
        val noQuery = build(
            state(
                guildStickers = emptyMap(),
                enabledStickerPacks = emptyList(),
                meUser = user(PremiumTier.TIER_2),
            ),
        )
        assertTrue(noQuery is StickerPickerViewModel.ViewState.Stickers)
    }

    @Test
    fun `category ranges index into the grid so the rail can follow the scroll`() {
        val state = state(
            frequentlyUsedStickerIds = listOf(GUILD_STICKER_A.id),
            meUser = user(PremiumTier.TIER_2),
        )

        val viewState = build(state) as StickerPickerViewModel.ViewState.Stickers

        // recents: header + 1 sticker = [0, 2); guild: header + 2 = [2, 5); pack: header + 1 = [5, 7)
        assertEquals(
            listOf(0 to 2, 2 to 5, 5 to 7),
            viewState.categoryItems.map { it.categoryRange },
        )
        assertEquals(
            listOf(
                StickerCategoryItem.RECENT_CATEGORY_ID,
                GUILD.id,
                PACK.id,
            ),
            viewState.categoryItems.map { it.categoryId },
        )
    }

    @Test
    fun `guild stickers from elsewhere are hidden without the external-stickers permission`() {
        val elsewhere = channel(type = Channel.GUILD_TEXT, guildId = 999L)
        val viewState = build(
            state(
                meUser = user(PremiumTier.TIER_2),
                currentChannel = elsewhere,
                currentChannelPermissions = 0L,
            ),
        ) as StickerPickerViewModel.ViewState.Stickers

        assertTrue(viewState.stickerItems.none { it.key == "sticker:${GUILD_STICKER_A.id}" })

        val allowed = build(
            state(
                meUser = user(PremiumTier.TIER_2),
                currentChannel = elsewhere,
                currentChannelPermissions = Permission.USE_EXTERNAL_STICKERS,
            ),
        ) as StickerPickerViewModel.ViewState.Stickers
        assertTrue(allowed.stickerItems.any { it.key == "sticker:${GUILD_STICKER_A.id}" })
    }

    // region fixtures

    private fun build(
        state: StickerPickerViewModel.StoreState,
        resolveSticker: (Long) -> Sticker? = { null },
    ): StickerPickerViewModel.ViewState =
        StickerPickerViewStateBuilder.build(state, Locale.US, resolveSticker)

    private fun state(
        allGuilds: Map<Long, Guild> = mapOf(GUILD.id to GUILD),
        guildStickers: Map<Long, Map<Long, Sticker>> = mapOf(
            GUILD.id to linkedMapOf(
                GUILD_STICKER_A.id to GUILD_STICKER_A,
                GUILD_STICKER_B.id to GUILD_STICKER_B,
            ),
        ),
        enabledStickerPacks: List<ModelStickerPack> = listOf(PACK),
        frequentlyUsedStickerIds: List<Long> = emptyList(),
        searchQuery: String = "",
        selectedCategoryId: Long? = null,
        meUser: MeUser = user(PremiumTier.TIER_2),
        currentChannel: Channel? = channel(type = Channel.GUILD_TEXT, guildId = GUILD.id),
        currentChannelPermissions: Long = Permission.USE_EXTERNAL_STICKERS,
    ) = StickerPickerViewModel.StoreState(
        allGuilds = allGuilds,
        guildStickers = guildStickers,
        enabledStickerPacks = enabledStickerPacks,
        frequentlyUsedStickerIds = frequentlyUsedStickerIds,
        searchQuery = searchQuery,
        stickerAnimationSettings = 0,
        selectedCategoryId = selectedCategoryId,
        meUser = meUser,
        isStickersSelectedTab = true,
        currentChannel = currentChannel,
        currentChannelPermissions = currentChannelPermissions,
        isOnCooldown = false,
    )

    private fun user(tier: PremiumTier) = MeUser(
        id = 1L, username = "me", avatar = null, banner = null, isBot = false,
        isSystemUser = false, discriminator = 1, premiumTier = tier, email = null,
        mfaEnabled = false, isVerified = true, token = null, flags = 0, publicFlags = 0,
        phoneNumber = null, nsfwAllowance = NsfwAllowance.UNKNOWN, bio = null, bannerColor = null,
    )

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

    // endregion

    private companion object {
        val GUILD = Guild(id = 7L, name = "Guild")

        fun sticker(
            id: Long,
            name: String = "sticker$id",
            tags: String = "",
            type: StickerType = StickerType.STANDARD,
            packId: Long? = null,
            guildId: Long? = null,
        ) = Sticker(
            id = id,
            packId = packId,
            guildId = guildId,
            name = name,
            description = "",
            formatType = StickerFormatType.PNG,
            tags = tags,
            type = type,
            available = true,
        )

        val GUILD_STICKER_A =
            sticker(id = 1L, name = "hug", type = StickerType.GUILD, guildId = GUILD.id)
        val GUILD_STICKER_B = sticker(
            id = 2L,
            name = "confetti",
            tags = "party,celebrate",
            type = StickerType.GUILD,
            guildId = GUILD.id,
        )
        val PACK_STICKER = sticker(id = 3L, name = "wumpus", packId = 100L)
        val PACK = ModelStickerPack(
            id = 100L,
            stickers = listOf(PACK_STICKER),
            name = "Wumpus Pack",
            storeListing = null,
            skuId = 1L,
            coverStickerId = null,
            description = null,
            bannerAssetId = null,
        )
    }
}
