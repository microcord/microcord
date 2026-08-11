package com.discord.stores

import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerType
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.stores.StoreMediaFavorites.Companion.toEntry
import com.discord.stores.StoreMediaFavorites.Companion.toProto
import com.discord.utilities.media.MediaFrecencyTracker
import discord_protos.discord_users.v1.FrecencyUserSettingsOuterClass.FrecencyUserSettings
import com.discord.utilities.persister.Persister
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.discord.widgets.stickers.StickerStore
import com.discord.widgets.stickers.StickerStore.StickerPackState
import rx.Observable

/**
 * Standard (Nitro / shop) sticker packs.
 *
 * Packs arrive from two places and the store has to keep them distinguishable: the sticker
 * *directory* (`GET /sticker-packs`) lists the packs this account may send from but omits each
 * pack's store listing, while a single-pack fetch includes it. [handleNewLoadedStickerPacks]
 * therefore refuses to overwrite an already-loaded pack that carries a listing, or opening the shop
 * after the picker would drop the purchase state.
 *
 * Guild uploads live in [StoreGuildStickers]; this store never sees them.
 *
 * Implements [StickerStore] so `StickerStores.stickers` can be repointed here in one line, which
 * retires `RestBackedStickerStore`.
 */
class StoreStickers(
    private val dispatcher: Dispatcher,
    private val api: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val clock: Clock = ClockFactory.get(),
    private val frecencyCache: Persister<MediaFrecencyTracker> = Persister(
        STICKER_FRECENCY_CACHE_KEY,
        MediaFrecencyTracker(MAX_FREQUENTLY_USED_STICKERS, STICKER_MIN_SCORE_THRESHOLD),
    ),
) : StoreV2(), StickerStore {

    private val frecency: MediaFrecencyTracker = frecencyCache.get()

    /** Sticker frecency is the account's, but is only serialised when a write is already going out. */
    val beforeSend = object : UserSettingsProtoWriter.BeforeSend<FrecencyUserSettings.Builder>() {
        override fun writeInto(builder: FrecencyUserSettings.Builder) {
            builder.stickerFrecencyBuilder.clearStickers().putAllStickers(
                frecency.toEntries(MAX_SYNCED_STICKERS)
                    .mapNotNull { (key, entry) -> key.toLongOrNull()?.to(entry.toProto()) }
                    .toMap(),
            )
        }
    }

    @StoreThread
    fun handleFrecencySettings(settings: FrecencyUserSettings) {
        frecency.restore(settings.stickerFrecency.stickersMap.mapKeys { it.key.toString() }.mapValues { it.value.toEntry() })
        frecencyCache.set(frecency, persist = true)
    }

    private var stickerPacks: Map<Long, StickerPackState> = emptyMap()
    private var stickerPacksSnapshot: Map<Long, StickerPackState> = emptyMap()
    private var stickersSnapshot: Map<Long, Sticker> = emptyMap()

    private var enabledStickerPacks: List<ModelStickerPack> = emptyList()
    private var enabledStickerPacksSnapshot: List<ModelStickerPack> = emptyList()
    private var enabledStickerPacksStickersSnapshot: List<Sticker> = emptyList()

    private var lastFetchedEnabledPacks: Long = 0

    fun init() {
        fetchEnabledStickerDirectory()
    }

    // region reads

    override fun getStickerPack(stickerPackId: Long): StickerPackState? =
        stickerPacksSnapshot[stickerPackId]

    override fun getStickerPacks(): Map<Long, StickerPackState> = stickerPacksSnapshot

    override fun getStickers(): Map<Long, Sticker> = stickersSnapshot

    override fun getEnabledStickerPacks(): List<ModelStickerPack> = enabledStickerPacksSnapshot

    override fun getEnabledStickers(): List<Sticker> = enabledStickerPacksStickersSnapshot

    /** Subscribing is what triggers the first fetch, so a caller never has to prime the store. */
    override fun observeStickerPack(stickerPackId: Long): Observable<StickerPackState> {
        dispatcher.schedule {
            if (!stickerPacks.containsKey(stickerPackId)) fetchStickerPack(stickerPackId)
        }
        return observationDeck.connectRx(this) {
            stickerPacks[stickerPackId] ?: StickerPackState.Unknown
        }
    }

    override fun observeStickerPacks(): Observable<List<StickerPackState>> =
        observationDeck.connectRx(this) { stickerPacks.values.toList() }

    override fun observeEnabledStickerPacks(): Observable<List<ModelStickerPack>> =
        observationDeck.connectRx(this) { enabledStickerPacksSnapshot }

    /**
     * Reads through the persister rather than the observation deck: recents survive process death
     * and must be available before the store thread has run anything.
     */
    override fun observeFrequentlyUsedStickerIds(): Observable<List<Long>> =
        frecencyCache.getObservable().map { tracker ->
            tracker.getSortedKeys().mapNotNull(String::toLongOrNull)
        }

    // endregion

    // region fetching

    /** Throttled to one call per five minutes; the directory changes on the order of weeks. */
    override fun fetchEnabledStickerDirectory() {
        if (lastFetchedEnabledPacks + FETCH_ENABLED_STICKER_PACKS_DELAY_MS >= clock.currentTimeMillis()) return
        val api = api() ?: return
        api.getStickerPacks()
            .restSubscribeOn()
            .appSubscribe(StoreStickers::class.java) { directory ->
                dispatcher.schedule {
                    lastFetchedEnabledPacks = clock.currentTimeMillis()
                    handleNewEnabledStickerDirectory(directory.stickerPacks)
                }
            }
    }

    /**
     * Not idempotent, matching the reference: [observeStickerPack] is where the "already known"
     * guard lives, and `StickerUtils` only calls this for a sticker it could not resolve locally.
     */
    override fun fetchStickerPack(stickerPackId: Long) = dispatcher.schedule {
        handleNewLoadingStickerPacks(listOf(stickerPackId))
        val api = api() ?: return@schedule
        api.getStickerPack(stickerPackId)
            .restSubscribeOn()
            .appSubscribe(StoreStickers::class.java) { newPack ->
                dispatcher.schedule { handleNewLoadedStickerPacks(listOf(newPack)) }
            }
    }

    /** A standard sticker seen on a message: pull in the pack so the popout can describe it. */
    override fun handleFetchedSticker(sticker: Sticker?) {
        if (sticker == null || sticker.type != StickerType.STANDARD) return
        fetchStickerPack(requireNotNull(sticker.packId) { "standard sticker without a pack id" })
    }

    // endregion

    // region frecency

    override fun onStickerUsed(sticker: Sticker) = dispatcher.schedule {
        frecency.track(sticker.id.toString())
        frecencyCache.set(frecency, persist = true)
        beforeSend.markDirty()
    }

    /**
     * A guild sticker was deleted: forget it, or the "Recent" row keeps a slot for something that
     * can never be sent again.
     */
    fun handleDeletedStickers(stickerIds: List<Long>) = dispatcher.schedule {
        stickerIds.forEach { frecency.removeEntry(it.toString()) }
        frecencyCache.set(frecency, persist = true)
        beforeSend.markDirty()
    }

    @StoreThread
    fun handlePreLogout() {
        frecencyCache.clear()
    }

    // endregion

    // region store-thread mutations

    @StoreThread
    fun handleNewEnabledStickerDirectory(enabledPacks: List<ModelStickerPack>) {
        handleNewLoadedStickerPacks(enabledPacks)
        enabledStickerPacks = enabledPacks
        markChanged()
    }

    @StoreThread
    fun handleNewLoadedStickerPacks(newStickerPacks: List<ModelStickerPack>) {
        val next = stickerPacks.toMutableMap()
        for (pack in newStickerPacks) {
            val existing = next[pack.id]
            // Keep a pack that already knows its store listing: the directory response omits the
            // listing, so overwriting would lose the price and availability the shop needs.
            val keepExisting = existing is StickerPackState.Loaded && existing.stickerPack.storeListing != null
            if (!keepExisting) next[pack.id] = StickerPackState.Loaded(pack)
        }
        stickerPacks = next
        markChanged()
    }

    @StoreThread
    fun handleNewLoadingStickerPacks(stickerPackIds: List<Long>) {
        val next = stickerPacks.toMutableMap()
        stickerPackIds.forEach { next[it] = StickerPackState.Loading }
        stickerPacks = next
        markChanged()
    }

    @StoreThread
    internal fun reset() {
        stickerPacks = emptyMap()
        enabledStickerPacks = emptyList()
        lastFetchedEnabledPacks = 0
        markChanged()
    }

    // endregion

    @StoreThread
    override fun snapshotData() {
        stickerPacksSnapshot = HashMap(stickerPacks)
        stickersSnapshot = stickerPacksSnapshot.values
            .filterIsInstance<StickerPackState.Loaded>()
            .flatMap { it.stickerPack.stickers }
            .associateBy(Sticker::id)
        enabledStickerPacksSnapshot = ArrayList(enabledStickerPacks)
        enabledStickerPacksStickersSnapshot = enabledStickerPacks.flatMap(ModelStickerPack::stickers)
    }

    companion object {
        const val STICKER_FRECENCY_CACHE_KEY = "STICKER_HISTORY_V1"

        private const val FETCH_ENABLED_STICKER_PACKS_DELAY_MS = 300_000L
        private const val MAX_FREQUENTLY_USED_STICKERS = 20

        /** What the account keeps, as opposed to what one picker row shows. */
        private const val MAX_SYNCED_STICKERS = 100

        /**
         * One, not the emoji tracker's seventy: a sticker is sent far less often than an emoji, so
         * the higher threshold would empty the "Recent" row within days.
         */
        private const val STICKER_MIN_SCORE_THRESHOLD = 1
    }
}
