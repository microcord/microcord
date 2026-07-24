package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import rx.Observable
import rx.subjects.PublishSubject

data class GuildSubscriptionPayload(
    val guildId: Long,
    val channels: Map<Long, List<List<Int>>>,
)

class StoreGuildSubscriptions(
    private val observationDeck: ObservationDeck,
    private val onSubscription: (GuildSubscriptionPayload) -> Unit = {},
) : StoreV2() {
    private val ranges = LinkedHashMap<Long, MutableMap<Long, List<IntRange>>>()
    private var rangesSnapshot: Map<Long, Map<Long, List<IntRange>>> = emptyMap()
    private val events = PublishSubject.create<GuildSubscriptionPayload>()

    @StoreThread
    fun handlePanelOpened(guildId: Long, channelId: Long) = setRanges(guildId, channelId, listOf(INITIAL_RANGE))

    @StoreThread
    fun handleViewportChanged(guildId: Long, channelId: Long, visibleRange: IntRange) =
        setRanges(guildId, channelId, computeRanges(visibleRange))

    @StoreThread
    fun handlePanelClosed(guildId: Long, channelId: Long) {
        val guildRanges = ranges[guildId] ?: return
        if (guildRanges.remove(channelId) == null) return
        if (guildRanges.isEmpty()) ranges.remove(guildId)
        publish(guildId, guildRanges)
    }

    @StoreThread
    fun handleGuildRemove(guildId: Long) {
        if (ranges.remove(guildId) != null) {
            markChanged()
            onSubscription(GuildSubscriptionPayload(guildId, emptyMap()))
        }
    }

    @StoreThread
    fun reset() {
        if (ranges.isEmpty()) return
        val guildIds = ranges.keys.toList()
        ranges.clear()
        markChanged()
        guildIds.forEach { onSubscription(GuildSubscriptionPayload(it, emptyMap())) }
    }

    fun get(guildId: Long): Map<Long, List<IntRange>> = rangesSnapshot[guildId].orEmpty()

    fun observe(guildId: Long): Observable<Map<Long, List<IntRange>>> =
        observationDeck.connectRx(this) { get(guildId) }.distinctUntilChanged()
    fun observeSubscriptions(): Observable<GuildSubscriptionPayload> = events

    @StoreThread
    fun handleConnectionReady(ready: Boolean) {
        if (!ready) return
        ranges.forEach { (guildId, channels) -> publish(guildId, channels) }
    }

    override fun snapshotData() {
        rangesSnapshot = ranges.mapValues { (_, channels) -> channels.mapValues { it.value.toList() } }
    }

    private fun setRanges(guildId: Long, channelId: Long, newRanges: List<IntRange>) {
        if (guildId <= 0 || channelId <= 0) return
        val guildRanges = ranges.getOrPut(guildId) { LinkedHashMap() }
        if (guildRanges[channelId] == newRanges) return
        guildRanges[channelId] = newRanges
        publish(guildId, guildRanges)
    }

    private fun publish(guildId: Long, guildRanges: Map<Long, List<IntRange>>) {
        markChanged()
        val payload = GuildSubscriptionPayload(
            guildId,
            guildRanges.mapValues { (_, value) -> value.map { listOf(it.first, it.last) } },
        )
        events.onNext(payload)
        onSubscription(payload)
    }

    companion object {
        private const val CHUNK_SIZE = 100
        val INITIAL_RANGE = 0..99

        fun computeRanges(visibleRange: IntRange): List<IntRange> {
            if (visibleRange.isEmpty()) return listOf(INITIAL_RANGE)
            val firstChunk = visibleRange.first.coerceAtLeast(0) / CHUNK_SIZE
            val lastChunk = (visibleRange.last - 1).coerceAtLeast(visibleRange.first).coerceAtLeast(0) / CHUNK_SIZE
            return (listOf(0) + (firstChunk..lastChunk)).distinct().map { chunk ->
                val start = chunk * CHUNK_SIZE
                start..(start + CHUNK_SIZE - 1)
            }
        }
    }
}
