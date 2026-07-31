package com.discord.widgets.chat.input.sticker

import com.discord.api.sticker.Sticker
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.user.UserUtils
import com.discord.widgets.stickers.StickerSendability
import com.discord.widgets.stickers.getStickerSendability
import java.util.Locale

/**
 * Turns [StickerPickerViewModel.StoreState] into what the picker draws.
 *
 * Kept out of the view model and free of every store so the ordering, the search filter and the
 * category ranges — the parts that are easy to get subtly wrong — can be tested directly.
 */
internal object StickerPickerViewStateBuilder {

    /**
     * The four stickers the Nitro pitch shows. Wire ids of Discord-published stickers, hard-coded
     * in the reference too; they resolve only once the sticker directory has been fetched.
     */
    val EMPTY_STATE_STICKER_IDS = listOf(
        781323769960202280L,
        809209266556764241L,
        818597810047680532L,
        819129296374595614L,
    )

    /**
     * @param resolveSticker looks a sticker up by id for the Nitro pitch's sample art.
     */
    fun build(
        state: StickerPickerViewModel.StoreState,
        locale: Locale,
        resolveSticker: (Long) -> Sticker?,
    ): StickerPickerViewModel.ViewState {
        val allById = allStickersById(state)
        val search = state.searchQuery.lowercase(locale)
        val canUsePremium = with(UserUtils) { state.meUser.canUsePremiumStickers }

        val frequentlyUsed = state.frequentlyUsedStickerIds
            .mapNotNull { allById[it] }
            .filter { sendabilityOf(it, state) != StickerSendability.NONSENDABLE }

        val items = mutableListOf<MGRecyclerDataPayload>()

        val recentMatches = frequentlyUsed
            .filter { it.matches(search, locale) }
            .map {
                StickerItem(
                    sticker = it,
                    stickerAnimationSettings = state.stickerAnimationSettings,
                    mode = StickerItem.Mode.OWNED,
                    sendability = sendabilityOf(it, state),
                )
            }
        if (recentMatches.isNotEmpty()) {
            items += HeaderItem(HeaderType.Recent)
            items += recentMatches
        }

        for ((guildId, guild) in state.allGuilds) {
            val stickers = state.guildStickers[guildId]?.values?.toList() ?: continue
            if (!state.canUseExternalStickersInCurrentChannel(guildId)) continue
            items += buildSection(
                stickers = stickers,
                header = HeaderType.GuildItem(guild),
                search = search,
                locale = locale,
                state = state,
                useChannelContext = true,
            )
        }

        for (pack in state.enabledStickerPacks) {
            if (pack.stickers.isEmpty()) continue
            items += buildSection(
                stickers = pack.stickers,
                header = HeaderType.PackItem(pack),
                search = search,
                locale = locale,
                state = state,
                useChannelContext = false,
            )
        }

        val categories = buildCategoryItems(state, items, frequentlyUsed)

        // Nothing to send and nothing to unlock by joining a server: pitch Nitro instead.
        if (!canUsePremium && state.guildStickers.isEmpty() && state.enabledStickerPacks.isEmpty()) {
            return StickerPickerViewModel.ViewState.EmptyNonPremium(
                emptyStateStickers = EMPTY_STATE_STICKER_IDS.mapNotNull(resolveSticker),
                isStickersSelectedTab = state.isStickersSelectedTab,
            )
        }
        // An empty grid with no search behind it is a genuinely empty picker, not a failed search.
        if (items.isEmpty() && search.isNotEmpty()) {
            return StickerPickerViewModel.ViewState.EmptySearchResults(state.searchQuery, categories)
        }
        return StickerPickerViewModel.ViewState.Stickers(
            searchQuery = state.searchQuery,
            stickerItems = items,
            categoryItems = categories,
            isStickersSelectedTab = state.isStickersSelectedTab,
            isOnCooldown = state.isOnCooldown,
        )
    }

    /**
     * `stickerId -> Sticker` across everything the user owns. Guild stickers go in last so they win
     * a collision, matching the reference.
     */
    private fun allStickersById(state: StickerPickerViewModel.StoreState): Map<Long, Sticker> {
        val all = LinkedHashMap<Long, Sticker>()
        state.enabledStickerPacks.forEach { pack -> pack.stickers.forEach { all[it.id] = it } }
        state.guildStickers.values.forEach { stickers -> all.putAll(stickers) }
        return all
    }

    /** A section contributes nothing at all — not even its header — when the search empties it. */
    private fun buildSection(
        stickers: List<Sticker>,
        header: HeaderType,
        search: String,
        locale: Locale,
        state: StickerPickerViewModel.StoreState,
        useChannelContext: Boolean,
    ): List<MGRecyclerDataPayload> {
        val matches = stickers.filter { it.matches(search, locale) }.map { sticker ->
            StickerItem(
                sticker = sticker,
                stickerAnimationSettings = state.stickerAnimationSettings,
                mode = StickerItem.Mode.OWNED,
                // Standard packs are channel-independent; guild stickers are not.
                sendability = if (useChannelContext) {
                    sendabilityOf(sticker, state)
                } else {
                    getStickerSendability(sticker, state.meUser)
                },
            )
        }
        if (matches.isEmpty()) return emptyList()
        return listOf(HeaderItem(header)) + matches
    }

    private fun sendabilityOf(
        sticker: Sticker,
        state: StickerPickerViewModel.StoreState,
    ): StickerSendability = getStickerSendability(
        sticker,
        state.meUser,
        state.currentChannel,
        state.currentChannelPermissions,
    )

    /** Name or comma-separated tags, case-folded in the same locale the query was. */
    private fun Sticker.matches(search: String, locale: Locale): Boolean =
        search.isEmpty() ||
            name.lowercase(locale).contains(search) ||
            tags.lowercase(locale).contains(search)

    /**
     * The tab rail, with each tab's `[start, end)` span into [listItems].
     *
     * A tab is only offered when at least one of its stickers survived the search, but the running
     * cursor advances by the *unfiltered* section size — reproducing the reference, where the tab
     * ranges therefore stop lining up with the grid while a search is active. Fixing it would change
     * which tab highlights as a filtered list scrolls, so it is left alone deliberately.
     */
    private fun buildCategoryItems(
        state: StickerPickerViewModel.StoreState,
        listItems: List<MGRecyclerDataPayload>,
        frequentlyUsed: List<Sticker>,
    ): List<StickerCategoryItem> {
        if (state.guildStickers.isEmpty() && state.enabledStickerPacks.isEmpty()) {
            return emptyList()
        }
        val visiblePackIds = LinkedHashSet<Long>()
        val visibleGuildIds = LinkedHashSet<Long>()
        for (item in listItems) {
            val sticker = (item as? StickerItem)?.sticker ?: continue
            if (frequentlyUsed.any { it.id == sticker.id }) {
                visiblePackIds += StickerCategoryItem.RECENT_CATEGORY_ID
            }
            sticker.packId?.let { visiblePackIds += it }
            sticker.guildId?.let { visibleGuildIds += it }
        }

        val out = mutableListOf<StickerCategoryItem>()
        val selectedId = state.selectedCategoryId
        var cursor = 0

        if (frequentlyUsed.isNotEmpty()) {
            cursor = frequentlyUsed.size + 1 // +1 for the "Frequently used" header row
            if (StickerCategoryItem.RECENT_CATEGORY_ID in visiblePackIds) {
                out += StickerCategoryItem.RecentItem(
                    isSelected = selectedId == StickerCategoryItem.RECENT_CATEGORY_ID,
                    categoryRange = 0 to cursor,
                )
            }
        }

        for ((guildId, guild) in state.allGuilds) {
            val stickers = state.guildStickers[guildId]?.values?.toList() ?: continue
            if (!state.canUseExternalStickersInCurrentChannel(guildId)) continue
            val end = cursor + stickers.size + 1
            if (guildId in visibleGuildIds) {
                out += StickerCategoryItem.GuildItem(
                    guild = guild,
                    stickers = stickers,
                    categoryRange = cursor to end,
                    isSelected = selectedId == guild.id,
                )
            }
            cursor = end
        }

        for (pack in state.enabledStickerPacks) {
            val end = cursor + pack.stickers.size + 1
            if (pack.id in visiblePackIds) {
                out += StickerCategoryItem.PackItem(
                    pack = pack,
                    categoryRange = cursor to end,
                    isSelected = selectedId == pack.id,
                )
            }
            cursor = end
        }
        return out
    }
}
