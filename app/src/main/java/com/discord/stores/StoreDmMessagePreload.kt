package com.discord.stores

import com.discord.api.channel.Channel
import com.discord.api.channel.getSortByMostRecent
import com.discord.api.channel.isDM
import com.discord.api.channel.isMultiUserDM
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rx.restSubscribeOn
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.Subscription
import rx.schedulers.Schedulers

/**
 * Bulk-preloads the newest messages of the DM list's channels, the way the RN client's
 * `MessagePreviewManager` does.
 *
 * RN derives its "wants" from the rows that are actually rendered without a preview, so its
 * request always matches the visible list — including after the gateway reconnects and reorders
 * it. The ordering is therefore not snapshotted here: this store observes the same sources the
 * DM list renders from (channels, `StoreMessagesMostRecent`, guild settings' pinned bucket, the
 * preview cache), computes the visible ordering — pinned first, then most-recent — and requests
 * the first [BATCH_SIZE] channels that still lack a preview. Each response updates
 * [StoreMessagePreviews], which re-triggers the chain for the next batch until nothing is
 * missing; RN's tick loop works the same way, just driven by its rows instead.
 */
class StoreDmMessagePreload(
    private val dispatcher: Dispatcher,
    private val storeMessagePreviews: StoreMessagePreviews,
    private val storeChannels: StoreChannels,
    private val storeMessagesMostRecent: StoreMessagesMostRecent,
    private val storeUserGuildSettings: StoreUserGuildSettings,
    private val api: () -> RestAPIInterface? = { null },
) {
    private var preloadedForGeneration = -1L
    private var subscription: Subscription? = null

    /** Channels already requested this auth generation, so an empty response is not re-asked forever. */
    private val requested = HashSet<Long>()

    /** One reactive preload chain per auth generation; re-armed on the next READY after re-auth. */
    fun preloadIfEnabled(enabled: Boolean, authGeneration: Long) {
        if (!enabled || authGeneration <= 0L) return
        synchronized(this) {
            if (preloadedForGeneration == authGeneration) return
            preloadedForGeneration = authGeneration
            requested.clear()
        }
        val restApi = api() ?: return

        subscription?.unsubscribe()
        subscription = Observable.combineLatest(
            storeChannels.observeChannelsForGuild(PRIVATE_CHANNELS_GUILD_ID),
            storeMessagesMostRecent.observeRecentMessageIds(),
            storeUserGuildSettings.observeGuildSettings(PRIVATE_CHANNELS_GUILD_ID),
            storeMessagePreviews.observePreviews(),
        ) { channels, mostRecentIds, settings, previews ->
            // The exact ordering the DM list renders: the pinned bucket above the rest, each
            // ordered by recency. A boot-time `lastMessageId` sort drifts away from this the
            // moment the gateway connects and the live list reorders.
            val pinnedIds = settings.channelOverrides
                .filter { it.isFavorite }
                .mapTo(HashSet()) { it.channelId }
            channels.values
                .filter { (it.isDM() || it.isMultiUserDM()) && it.id !in previews }
                .sortedWith(
                    compareBy<Channel> { it.id !in pinnedIds }
                        .then(Channel.getSortByMostRecent(mostRecentIds)),
                )
                .map(Channel::id)
        }
            .distinctUntilChanged()
            // Coalesces the reorder burst that follows READY (presences, live messages), the same
            // job RN's 100 ms remote-tick debounce does for its want set.
            .debounce(DEBOUNCE_MS, TimeUnit.MILLISECONDS)
            .observeOn(Schedulers.io())
            .subscribe(
                { wants -> fetchMissing(restApi, wants, authGeneration) },
                { },
            )
    }

    private fun fetchMissing(restApi: RestAPIInterface, wants: List<Long>, authGeneration: Long) {
        val batch = synchronized(requested) {
            val next = wants.filterNot(requested::contains).take(BATCH_SIZE)
            requested.addAll(next)
            next
        }
        if (batch.isEmpty()) return

        restApi.preloadMessages(RestAPIParams.PreloadMessages(channelIds = batch))
            .restSubscribeOn()
            .subscribe(
                { messages ->
                    dispatcher.schedule {
                        storeMessagePreviews.handlePreviewsLoaded(messages, authGeneration)
                    }
                },
                {
                    // A failed batch becomes eligible again on the next organic re-emission (any
                    // reorder or preview change); no synthetic retry, so a dead network cannot
                    // loop. RN retries up to five ticks — this retries on list change instead.
                    synchronized(requested) { batch.forEach(requested::remove) }
                },
            )
    }

    private companion object {
        /** The pseudo-guild the private channels are filed under. */
        const val PRIVATE_CHANNELS_GUILD_ID = 0L

        /** RN's per-request batch size; the endpoint itself rejects more than 100. */
        const val BATCH_SIZE = 30

        const val DEBOUNCE_MS = 250L
    }
}
