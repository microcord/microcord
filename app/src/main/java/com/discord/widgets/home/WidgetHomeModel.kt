package com.discord.widgets.home

import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isThread
import com.discord.api.user.NsfwAllowance
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.presence.Presence
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreStream
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.widgets.chat.list.CreateThreadsFeatureFlag
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * Everything the home surface's chrome needs about the current selection: the action-bar title and
 * icon, the toolbar menu, the unread badge and the NSFW gate all come from one snapshot.
 *
 * [channel] and [channelId] are derived, not passed: the selection is authoritative and a channel is
 * only one of the shapes it can take (a thread draft has no channel at all).
 *
 * The composite is throttled on its leading edge with a 350 ms window because it fans in eight
 * stores; without that, opening a channel would re-render the toolbar once per store as they settle.
 */
data class WidgetHomeModel(
    val selectedChannel: StoreChannelsSelected.ResolvedSelectedChannel,
    val selectedVoiceChannel: Channel?,
    val parentChannel: Channel?,
    val dmPresence: Presence?,
    val unreadCount: Int,
    val isFriend: Boolean,
    val isCallConnected: Boolean,
    val nsfwAllowed: NsfwAllowance?,
    val threadCount: Int,
    val threadExperimentEnabled: Boolean,
) {

    val channel: Channel? = selectedChannel.maybeChannel

    val channelId: Long = selectedChannel.maybeChannel?.id ?: 0L

    val isChannelNsfw: Boolean
        get() = channel != null && channel.nsfw

    /**
     * Whether the user still has to accept an age-restricted guild's gate. Note the reference reads
     * the store directly here rather than carrying the flag in the model.
     */
    val isNsfwUnConsented: Boolean
        get() = channel != null && guildNsfwGateAgreed(channel.guildId).not()

    companion object {

        /**
         * The store that records which age-restricted guilds have been accepted is not published by
         * `StoreStream` in this project yet, so the gate reads as "already agreed" and never blocks.
         * Point this at `StoreGuildsNsfw.isGuildNsfwGateAgreed` once the store is wired.
         */
        @JvmField
        var guildNsfwGateAgreed: (guildId: Long) -> Boolean = { true }

        fun get(): Observable<WidgetHomeModel> = Observable.combineLatest(
            StoreStream.getGuildSelected().observeSelectedGuildId(),
            StoreStream.getChannelsSelected().observeResolvedSelectedChannel(),
        ) { selectedGuildId, selectedChannel -> selectedGuildId to selectedChannel }
            .switchMap { (selectedGuildId, selectedChannel) -> observeFor(selectedGuildId, selectedChannel) }
            .distinctUntilChanged()

        /**
         * Whether the client has hydrated enough to replace the splash panel with the real surface.
         *
         * The reference watches `StoreStream`'s own initialised flag; that flag exists here but has
         * no accessor on the companion, so the closest public equivalent is used — channels being
         * hydrated for the authenticated user, which is set from the same READY handling.
         */
        fun observeInitialized(): Observable<Boolean> =
            StoreStream.getChannels().observeInitializedForAuthedUser()
                .delay(INITIALIZED_DELAY_MS, TimeUnit.MILLISECONDS)

        /**
         * A thread's header shows its parent's name as the subtitle, so the parent is resolved for
         * threads and for nothing else.
         */
        fun getParentChannelObservable(channel: Channel?): Observable<Channel?> =
            if (channel != null && channel.isThread() && channel.parentId != 0L) {
                StoreStream.getChannels().observeChannel(channel.parentId)
            } else {
                Observable.just(null)
            }

        private fun observeFor(
            selectedGuildId: Long,
            selectedChannel: StoreChannelsSelected.ResolvedSelectedChannel,
        ): Observable<WidgetHomeModel> {
            val channel = selectedChannel.maybeChannel
            // Active threads are counted against the parent for a thread, and against the channel
            // itself otherwise; there is nothing to count when nothing is selected.
            val threadParentId: Long? = when {
                channel == null -> null
                channel.isThread() -> channel.parentId
                else -> channel.id
            }
            return Observable.combineLatest(
                StoreStream.getUsers().observeMe(),
                StoreStream.getPresences().observeAllPresences(),
                StoreStream.getVoiceChannelSelected().observeSelectedChannel(),
                StoreStream.getMentions().observeTotalMentions(),
                StoreStream.getUserRelationships().observe(),
                StoreStream.getThreadsActive().observeActiveThreadsForChannel(selectedGuildId, threadParentId),
                getParentChannelObservable(channel),
                Observable.just(CreateThreadsFeatureFlag.isEnabled(selectedGuildId)),
            ) { me, presences, selectedVoiceChannel, unreadCount, relationships, activeThreads, parentChannel, threadsEnabled ->
                val dmRecipient = channel?.getDMRecipient()
                WidgetHomeModel(
                    selectedChannel = selectedChannel,
                    selectedVoiceChannel = selectedVoiceChannel,
                    parentChannel = parentChannel,
                    dmPresence = dmRecipient?.let { presences[it.id] },
                    unreadCount = unreadCount,
                    isFriend = relationships[dmRecipient?.id] == ModelUserRelationship.TYPE_FRIEND,
                    // "Connected" means the selected channel *is* the channel the call is in, so the
                    // toolbar offers "leave call" rather than "start call".
                    isCallConnected = channel?.isPrivate() == true &&
                        selectedVoiceChannel?.id == channel.id,
                    nsfwAllowed = me.nsfwAllowance,
                    threadCount = activeThreads.size,
                    threadExperimentEnabled = threadsEnabled,
                )
            }.leadingEdgeThrottle(THROTTLE_MS, TimeUnit.MILLISECONDS)
        }

        private const val THROTTLE_MS = 350L
        private const val INITIALIZED_DELAY_MS = 150L
    }
}
