package com.discord.widgets.stickers

import com.discord.api.sticker.Sticker
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.stores.StoreStream
import rx.Observable

/**
 * The standard (Discord-published) sticker catalogue.
 *
 * This is the contract the sticker popout and the sticker picker need. It is declared here, in the
 * feature package, and `com.discord.stores.StoreStickers` implements it, so [StickerStores.stickers]
 * now resolves to the real dispatcher-confined, `ObservationDeck`-backed, persisted store.
 */
interface StickerStore {

    /** Lifecycle of one pack. `Unknown` means "never asked for"; `Loading` means a fetch is live. */
    sealed class StickerPackState {
        object Unknown : StickerPackState()

        object Loading : StickerPackState()

        data class Loaded(val stickerPack: ModelStickerPack) : StickerPackState()
    }

    /**
     * Emits the state of one pack, kicking off a fetch the first time the pack is unknown. The
     * initial emission is synchronous, so a subscriber always sees `Loading`/`Unknown` before
     * `Loaded`.
     */
    fun observeStickerPack(stickerPackId: Long): Observable<StickerPackState>

    /** Every pack the client has heard of, in insertion order. */
    fun observeStickerPacks(): Observable<List<StickerPackState>>

    /** The packs the current user may actually send from — the sticker-directory response. */
    fun observeEnabledStickerPacks(): Observable<List<ModelStickerPack>>

    /** Most-recently/frequently used sticker ids, most relevant first. Drives the "Recent" tab. */
    fun observeFrequentlyUsedStickerIds(): Observable<List<Long>>

    fun getStickerPack(stickerPackId: Long): StickerPackState?

    fun getStickerPacks(): Map<Long, StickerPackState>

    /** Flattened `stickerId -> Sticker` across every loaded pack. */
    fun getStickers(): Map<Long, Sticker>

    fun getEnabledStickerPacks(): List<ModelStickerPack>

    fun getEnabledStickers(): List<Sticker>

    /** Idempotent; a pack already `Loading` or `Loaded` is not re-fetched. */
    fun fetchStickerPack(stickerPackId: Long)

    /** Throttled in the reference to one call per five minutes. */
    fun fetchEnabledStickerDirectory()

    /** Records a send so the sticker floats up the "Recent" tab. */
    fun onStickerUsed(sticker: Sticker)

    /** A standard sticker seen on a message: pull in the pack it belongs to. */
    fun handleFetchedSticker(sticker: Sticker?)
}

/**
 * Custom stickers uploaded to guilds, keyed `guildId -> stickerId -> Sticker`.
 *
 * Same story as [StickerStore]: the contract lives here in the feature package and
 * `com.discord.stores.StoreGuildStickers` implements it, so [StickerStores.guildStickers] resolves
 * to the real store — which, unlike the retired stand-in, also consumes `GUILD_STICKERS_UPDATE`.
 */
interface GuildStickerStore {

    fun observeGuildStickers(): Observable<Map<Long, Map<Long, Sticker>>>

    fun getAllGuildStickers(): Map<Long, Map<Long, Sticker>>

    fun getAllGuildStickersFlattened(): List<Sticker>

    fun getStickersForGuild(guildId: Long): Map<Long, Sticker>

    /** Searches every guild, because a sticker id alone does not say which guild owns it. */
    fun getGuildSticker(stickerId: Long): Sticker?

    /** A guild sticker seen on a message from a guild we are not in: remember it. */
    fun handleFetchedSticker(sticker: Sticker?)
}

/**
 * The single resolution point for the two sticker stores.
 *
 * Deliberately `val`s over `lazy`, not mutable nullable statics: the audit found screens across
 * this codebase routing store state through `@JvmField var` callbacks that silently render nothing
 * when nobody installed them. View models take their store as a defaulted constructor parameter and
 * tests pass a fake, so nothing here has to be swapped at runtime.
 *
 * Both resolve to the real store graph. `RestBackedStickerStore` and
 * `GuildsBackedGuildStickerStore` in `StickerStoreDefaults.kt` are now dead and can be deleted.
 */
object StickerStores {
    val stickers: StickerStore get() = StoreStream.getStickers()

    val guildStickers: GuildStickerStore get() = StoreStream.getGuildStickers()
}
