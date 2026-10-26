package com.discord.stores

import com.discord.api.channel.ChannelInfo
import com.discord.api.channel.ChannelMetadata
import com.discord.api.channel.VoiceChannelStartTimeUpdate
import com.discord.api.channel.VoiceChannelStatusUpdate
import com.discord.stores.updates.ObservationDeck
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import org.junit.Assert.assertEquals
import org.junit.Test
import rx.schedulers.TestScheduler

class StoreVoiceChannelInfoTest {
    @Test
    fun channelInfoReplacesTheGuildAndUpdatesMerge() {
        val scheduler = TestScheduler()
        val dispatcher = Dispatcher(scheduler)
        val deck = ObservationDeck()
        val requested = mutableListOf<Long>()
        val store = StoreVoiceChannelInfo(dispatcher, deck) { requested += it; true }
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
        val snapshots = mutableListOf<Map<Long, StoreVoiceChannelInfo.Info>>()
        store.observeInfo().subscribe(snapshots::add)

        store.fetchChannelInfo(1)
        store.fetchChannelInfo(1)
        dispatcher.schedule {
            store.handleChannelInfo(ChannelInfo(1, listOf(ChannelMetadata(10, "gaming", 100), ChannelMetadata(11, "old"))))
            store.handleChannelInfo(ChannelInfo(2, listOf(ChannelMetadata(20, "other guild"))))
        }
        scheduler.triggerActions()
        assertEquals(listOf(1L), requested)

        dispatcher.schedule {
            // A fresh reply for guild 1 drops channel 11, but leaves guild 2 alone.
            store.handleChannelInfo(ChannelInfo(1, listOf(ChannelMetadata(10, "gaming", 100))))
            store.handleStatusUpdate(VoiceChannelStatusUpdate(10, 1, null))
            store.handleStartTimeUpdate(VoiceChannelStartTimeUpdate(12, 1, 5))
        }
        scheduler.triggerActions()
        assertEquals(
            mapOf(
                10L to StoreVoiceChannelInfo.Info(null, 100_000L),
                12L to StoreVoiceChannelInfo.Info(null, 5_000L),
                20L to StoreVoiceChannelInfo.Info("other guild", null),
            ),
            snapshots.last(),
        )
    }

    @Test
    fun elapsedTimeMatchesDesktop() {
        assertEquals("0:13", WidgetChannelsListAdapter.formatElapsed(13_400))
        assertEquals("59:59", WidgetChannelsListAdapter.formatElapsed(3_599_000))
        assertEquals("1:00:05", WidgetChannelsListAdapter.formatElapsed(3_605_000))
        assertEquals("0:00", WidgetChannelsListAdapter.formatElapsed(-5_000))
    }
}
