package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.frecency.FrecencyTracker
import com.discord.widgets.user.search.ChannelFrecencyTracker
import discord_protos.discord_users.v1.FrecencyUserSettingsOuterClass.FrecencyUserSettings
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Which channels the account visits, and when the frecency proto is written at all.
 *
 * Frecency is recorded on every use but sent on a timer, so a session's worth of navigation costs
 * one request rather than one per jump. Leaving the foreground or losing the socket sends whatever
 * has accumulated, since neither is guaranteed to be followed by another tick.
 */
class StoreFrecency(
    private val dispatcher: Dispatcher,
    private val scheduler: ScheduledExecutorService,
    private val writer: () -> UserSettingsProtoWriter<FrecencyUserSettings, FrecencyUserSettings.Builder>,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    private val channels = ChannelFrecencyTracker()
    private var periodic: ScheduledFuture<*>? = null

    val beforeSend = object : UserSettingsProtoWriter.BeforeSend<FrecencyUserSettings.Builder>() {
        override fun writeInto(builder: FrecencyUserSettings.Builder) {
            builder.guildAndChannelFrecencyBuilder.clearGuildAndChannels().putAllGuildAndChannels(
                channels.toEntries(MAX_ENTRIES).mapValues { it.value.toProto() },
            )
        }
    }

    fun trackChannel(channelId: Long) {
        channels.track(channelId)
        beforeSend.markDirty()
        dispatcher.schedule { markChanged() }
    }

    fun getSortedChannelIds(): Collection<Long> = channels.getSortedKeys()

    @StoreThread
    fun handleFrecencySettings(settings: FrecencyUserSettings) {
        channels.restore(settings.guildAndChannelFrecency.guildAndChannelsMap.mapValues { it.value.toEntry() })
        markChanged()
    }

    @StoreThread
    fun handleConnectionOpen() {
        periodic?.cancel(false)
        periodic = scheduler.scheduleWithFixedDelay(
            { writer().flush() },
            FIRST_SAVE_SECONDS + jitter(FIRST_SAVE_JITTER_SECONDS),
            SAVE_INTERVAL_SECONDS + jitter(SAVE_INTERVAL_JITTER_SECONDS),
            TimeUnit.SECONDS,
        )
    }

    fun handleBackgrounded(backgrounded: Boolean) {
        if (backgrounded) writer().flush()
    }

    @StoreThread
    fun handlePreLogout() {
        periodic?.cancel(false)
        periodic = null
        channels.restore(emptyMap())
        markChanged()
    }

    private companion object {
        const val MAX_ENTRIES = 100
        const val FIRST_SAVE_SECONDS = 10L
        const val FIRST_SAVE_JITTER_SECONDS = 10L
        const val SAVE_INTERVAL_SECONDS = 2 * 60 * 60L
        const val SAVE_INTERVAL_JITTER_SECONDS = 10 * 60L

        /** Spreads every client's periodic write out, rather than having them all land together. */
        fun jitter(bound: Long) = (Math.random() * bound).toLong()

        fun FrecencyUserSettings.FrecencyItem.toEntry() =
            FrecencyTracker.Entry(totalUses, recentUsesList.toList(), frecency, score)

        fun FrecencyTracker.Entry.toProto(): FrecencyUserSettings.FrecencyItem =
            FrecencyUserSettings.FrecencyItem.newBuilder()
                .setTotalUses(totalUses)
                .addAllRecentUses(recentUses)
                .setFrecency(frecency)
                .setScore(score)
                .build()
    }
}
