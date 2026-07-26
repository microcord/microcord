package com.discord.stores

import com.discord.api.guild.Guild
import com.discord.api.sticker.GuildStickersUpdate
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerType
import com.discord.models.domain.ModelPayload
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.widgets.stickers.GuildStickerStore
import rx.Observable

/**
 * Stickers uploaded to guilds, keyed `guildId -> stickerId -> sticker`.
 *
 * Fed entirely from the gateway — `READY`, `GUILD_CREATE`/`GUILD_UPDATE` and
 * `GUILD_STICKERS_UPDATE` — with one exception: [handleFetchedSticker] files a sticker fetched by
 * id from a guild this account is not in, which is how the popout for a foreign sticker resolves.
 *
 * [onStickersDeleted] exists because a deleted guild sticker has to be evicted from
 * [StoreStickers]'s frecency history, and this store is the only place that can tell a delete from
 * an edit — it diffs the previous sticker ids against the update.
 *
 * Implements [GuildStickerStore] so `StickerStores.guildStickers` can be repointed here in one
 * line, retiring `GuildsBackedGuildStickerStore`.
 */
class StoreGuildStickers(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val onStickersDeleted: (List<Long>) -> Unit,
) : StoreV2(), GuildStickerStore {

    private val allGuildStickers = LinkedHashMap<Long, Map<Long, Sticker>>()
    private var allGuildStickersSnapshot: Map<Long, Map<Long, Sticker>> = emptyMap()
    private var allGuildStickersFlattenedSnapshot: List<Sticker> = emptyList()

    private var me: Long = 0

    // region reads

    override fun getAllGuildStickers(): Map<Long, Map<Long, Sticker>> = allGuildStickersSnapshot

    override fun getAllGuildStickersFlattened(): List<Sticker> = allGuildStickersFlattenedSnapshot

    @StoreThread
    fun getAllGuildStickersInternal(): Map<Long, Map<Long, Sticker>> = allGuildStickers

    override fun getStickersForGuild(guildId: Long): Map<Long, Sticker> =
        getAllGuildStickers()[guildId].orEmpty()

    @StoreThread
    fun getStickersForGuildInternal(guildId: Long): Map<Long, Sticker>? = allGuildStickers[guildId]

    /** A sticker id does not say which guild owns it, so every guild has to be searched. */
    override fun getGuildSticker(stickerId: Long): Sticker? =
        getAllGuildStickers().values.firstOrNull { it.containsKey(stickerId) }?.get(stickerId)

    override fun observeGuildStickers(): Observable<Map<Long, Map<Long, Sticker>>> =
        observationDeck.connectRx(this) { allGuildStickersSnapshot }.distinctUntilChanged()

    // endregion

    // region gateway input

    @StoreThread
    fun handleConnectionOpen(payload: ModelPayload) {
        me = payload.me?.id ?: 0
        payload.guilds.orEmpty().forEach(::handleGuildCreateOrUpdate)
    }

    /**
     * Ignores a guild whose payload does not include our own member. That is the lazy/unavailable
     * `GUILD_CREATE` shape, and treating it as "this guild has no stickers" would blank the
     * picker's guild section until a full guild update arrived.
     */
    @StoreThread
    fun handleGuildCreateOrUpdate(guild: Guild) {
        guild.members?.firstOrNull { it.user.id == me } ?: return
        updateStickers(guild.id, guild.stickers.orEmpty())
    }

    @StoreThread
    fun handleGuildRemove(guild: Guild) = handleGuildRemove(guild.id)

    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        if (!allGuildStickers.containsKey(guildId)) return
        allGuildStickers.remove(guildId)
        markChanged()
    }

    @StoreThread
    fun handleStickerUpdate(stickersUpdate: GuildStickersUpdate) {
        val guildId = stickersUpdate.guildId
        val remainingIds = stickersUpdate.stickers.map(Sticker::id).toSet()
        // Diffed against the published snapshot, not the live map — that is what the reference
        // does, and it matters when two updates land inside one dispatch: the second would
        // otherwise see the first's writes and report nothing as deleted.
        val deletedIds = allGuildStickersSnapshot[guildId]?.keys.orEmpty()
            .filterNot(remainingIds::contains)
        updateStickers(guildId, stickersUpdate.stickers)
        onStickersDeleted(deletedIds)
    }

    /** A guild sticker fetched by id, possibly from a guild we are not in. */
    override fun handleFetchedSticker(sticker: Sticker?) {
        if (sticker == null || sticker.type != StickerType.GUILD) return
        val guildId = sticker.guildId ?: return
        dispatcher.schedule {
            allGuildStickers[guildId] =
                LinkedHashMap(allGuildStickers[guildId].orEmpty()).apply { put(sticker.id, sticker) }
            markChanged()
        }
    }

    // endregion

    override fun snapshotData() {
        super.snapshotData()
        val flattened = ArrayList<Sticker>()
        val byGuild = LinkedHashMap<Long, Map<Long, Sticker>>()
        for ((guildId, stickers) in allGuildStickers) {
            byGuild[guildId] = stickers.toMap()
            flattened += stickers.values
        }
        allGuildStickersSnapshot = byGuild
        allGuildStickersFlattenedSnapshot = flattened.toList()
    }

    @StoreThread
    internal fun reset() {
        allGuildStickers.clear()
        markChanged()
    }

    /** An empty sticker list removes the guild entirely, so it never shows an empty picker tab. */
    @StoreThread
    private fun updateStickers(guildId: Long, stickers: Collection<Sticker>) {
        if (stickers.isEmpty()) {
            allGuildStickers.remove(guildId)
        } else {
            allGuildStickers[guildId] =
                stickers.associateByTo(LinkedHashMap<Long, Sticker>(), Sticker::id)
        }
        markChanged()
    }
}
