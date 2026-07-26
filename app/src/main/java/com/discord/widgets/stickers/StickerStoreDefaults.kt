package com.discord.widgets.stickers

import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerType
import com.discord.models.sticker.dto.ModelStickerPack
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import java.util.concurrent.atomic.AtomicBoolean
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Stand-in for `com.discord.stores.StoreStickers`, backed directly by the REST endpoints that are
 * already declared in [RestAPIInterface] but currently have no caller.
 *
 * Faithful to the reference in what it exposes and in the throttle on the directory fetch. It
 * deliberately does *not* reimplement the store machinery — there is no `Dispatcher` confinement,
 * no `ObservationDeck` registration, no `snapshotData()` split between live and snapshot maps, and
 * no `Persister("STICKER_HISTORY_V1")` frecency cache, so the "Recent" list is in-memory and does
 * not survive process death. Building any of that here would be a competing store; the point of
 * this class is that the sticker popout and picker work today instead of silently rendering
 * nothing, and that the day `StoreStickers` exists it can replace this file wholesale.
 */
internal class RestBackedStickerStore(
    private val api: RestAPIInterface = RestAPI.api,
    private val clock: Clock = ClockFactory.get(),
) : StickerStore {

    private val packsSubject =
        BehaviorSubject.create<Map<Long, StickerStore.StickerPackState>>(emptyMap())
    private val enabledPacksSubject = BehaviorSubject.create<List<ModelStickerPack>>(emptyList())
    private val recentStickerIdsSubject = BehaviorSubject.create<List<Long>>(emptyList())

    private val directoryFetchInFlight = AtomicBoolean(false)

    @Volatile
    private var lastFetchedEnabledPacks = 0L

    override fun observeStickerPack(stickerPackId: Long): Observable<StickerStore.StickerPackState> {
        fetchStickerPack(stickerPackId)
        return packsSubject
            .map { it[stickerPackId] ?: StickerStore.StickerPackState.Unknown }
            .distinctUntilChanged()
    }

    override fun observeStickerPacks(): Observable<List<StickerStore.StickerPackState>> =
        packsSubject.map { it.values.toList() }

    override fun observeEnabledStickerPacks(): Observable<List<ModelStickerPack>> {
        fetchEnabledStickerDirectory()
        return enabledPacksSubject
    }

    override fun observeFrequentlyUsedStickerIds(): Observable<List<Long>> = recentStickerIdsSubject

    override fun getStickerPack(stickerPackId: Long): StickerStore.StickerPackState? =
        getStickerPacks()[stickerPackId]

    override fun getStickerPacks(): Map<Long, StickerStore.StickerPackState> =
        packsSubject.value.orEmpty()

    override fun getStickers(): Map<Long, Sticker> =
        loadedPacks().flatMap { it.stickers }.associateBy { it.id }

    override fun getEnabledStickerPacks(): List<ModelStickerPack> =
        enabledPacksSubject.value.orEmpty()

    override fun getEnabledStickers(): List<Sticker> = getEnabledStickerPacks().flatMap { it.stickers }

    override fun fetchStickerPack(stickerPackId: Long) {
        synchronized(this) {
            if (getStickerPacks().containsKey(stickerPackId)) return
            putPackState(stickerPackId, StickerStore.StickerPackState.Loading)
        }
        api.getStickerPack(stickerPackId)
            .restSubscribeOn()
            .subscribe(
                { pack -> putLoadedPacks(listOf(pack)) },
                {
                    // Leave the pack `Loading` rather than pinning a failure: the popout keeps its
                    // spinner and a later subscriber retries, which is what the reference does.
                    synchronized(this) { removePackState(stickerPackId) }
                },
            )
    }

    override fun fetchEnabledStickerDirectory() {
        val now = clock.currentTimeMillis()
        if (lastFetchedEnabledPacks + FETCH_ENABLED_STICKER_PACKS_DELAY_MS >= now) return
        if (!directoryFetchInFlight.compareAndSet(false, true)) return
        api.getStickerPacks()
            .restSubscribeOn()
            .subscribe(
                { directory ->
                    lastFetchedEnabledPacks = clock.currentTimeMillis()
                    directoryFetchInFlight.set(false)
                    putLoadedPacks(directory.stickerPacks)
                    enabledPacksSubject.onNext(directory.stickerPacks)
                },
                { directoryFetchInFlight.set(false) },
            )
    }

    override fun onStickerUsed(sticker: Sticker) {
        synchronized(this) {
            val updated = (listOf(sticker.id) + recentStickerIdsSubject.value.orEmpty())
                .distinct()
                .take(MAX_FREQUENTLY_USED_STICKERS)
            recentStickerIdsSubject.onNext(updated)
        }
    }

    override fun handleFetchedSticker(sticker: Sticker?) {
        if (sticker == null || sticker.type != StickerType.STANDARD) return
        sticker.packId?.let(::fetchStickerPack)
    }

    private fun loadedPacks(): List<ModelStickerPack> =
        getStickerPacks().values.filterIsInstance<StickerStore.StickerPackState.Loaded>()
            .map { it.stickerPack }

    private fun putLoadedPacks(packs: List<ModelStickerPack>) {
        synchronized(this) {
            val next = getStickerPacks().toMutableMap()
            for (pack in packs) {
                // The reference keeps an existing Loaded pack that already carries a store listing:
                // the directory response omits it, so overwriting would drop the purchase state.
                val existing = next[pack.id]
                val keepExisting = existing is StickerStore.StickerPackState.Loaded &&
                    existing.stickerPack.storeListing != null
                if (!keepExisting) next[pack.id] = StickerStore.StickerPackState.Loaded(pack)
            }
            packsSubject.onNext(next)
        }
    }

    private fun putPackState(stickerPackId: Long, state: StickerStore.StickerPackState) {
        packsSubject.onNext(getStickerPacks() + (stickerPackId to state))
    }

    private fun removePackState(stickerPackId: Long) {
        packsSubject.onNext(getStickerPacks() - stickerPackId)
    }

    private companion object {
        const val FETCH_ENABLED_STICKER_PACKS_DELAY_MS = 300_000L
        const val MAX_FREQUENTLY_USED_STICKERS = 20
    }
}

/**
 * Stand-in for `com.discord.stores.StoreGuildStickers`, reading the sticker lists that
 * [StoreGuilds] already keeps on every joined `Guild`.
 *
 * The reference store is fed from exactly the same GUILD_CREATE/GUILD_UPDATE payloads, so joined
 * guilds are correct. Two gaps, both closed by the store wave:
 *  - `GUILD_STICKERS_UPDATE` is not routed into `StoreGuilds`, so a sticker added while the client
 *    is connected only appears after the next guild update.
 *  - [handleFetchedSticker] caches a sticker from a guild we are *not* in, which is how the popout
 *    for a foreign guild sticker resolves; the reference persists this into the store map.
 */
internal class GuildsBackedGuildStickerStore(
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
) : GuildStickerStore {

    private val fetchedSubject = BehaviorSubject.create<Map<Long, Map<Long, Sticker>>>(emptyMap())

    override fun observeGuildStickers(): Observable<Map<Long, Map<Long, Sticker>>> =
        Observable.combineLatest(storeGuilds.observeGuilds(), fetchedSubject) { guilds, fetched ->
            merge(guilds.values.associate { it.id to it.stickers.associateBy(Sticker::id) }, fetched)
        }.distinctUntilChanged()

    override fun getAllGuildStickers(): Map<Long, Map<Long, Sticker>> = merge(
        storeGuilds.getGuilds().values.associate { it.id to it.stickers.associateBy(Sticker::id) },
        fetchedSubject.value.orEmpty(),
    )

    override fun getAllGuildStickersFlattened(): List<Sticker> =
        getAllGuildStickers().values.flatMap { it.values }

    override fun getStickersForGuild(guildId: Long): Map<Long, Sticker> =
        getAllGuildStickers()[guildId].orEmpty()

    override fun getGuildSticker(stickerId: Long): Sticker? =
        getAllGuildStickers().values.firstOrNull { it.containsKey(stickerId) }?.get(stickerId)

    override fun handleFetchedSticker(sticker: Sticker?) {
        if (sticker == null || sticker.type != StickerType.GUILD) return
        val guildId = sticker.guildId ?: return
        synchronized(this) {
            val current = fetchedSubject.value.orEmpty()
            fetchedSubject.onNext(
                current + (guildId to (current[guildId].orEmpty() + (sticker.id to sticker))),
            )
        }
    }

    private fun merge(
        fromGuilds: Map<Long, Map<Long, Sticker>>,
        fetched: Map<Long, Map<Long, Sticker>>,
    ): Map<Long, Map<Long, Sticker>> {
        if (fetched.isEmpty()) return fromGuilds.filterValues { it.isNotEmpty() }
        val merged = fromGuilds.toMutableMap()
        for ((guildId, stickers) in fetched) {
            merged[guildId] = merged[guildId].orEmpty() + stickers
        }
        return merged.filterValues { it.isNotEmpty() }
    }
}
