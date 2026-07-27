package com.discord.widgets.home

import com.discord.api.channel.isVoiceChannel
import com.discord.panels.OverlappingPanelsLayout.LockState
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreGuildSelected
import com.discord.stores.StoreStream
import rx.Observable

/**
 * Decides whether the guild/channel list can be swiped in and out, from what is selected.
 *
 * Three outcomes:
 *
 * * [LockState.CLOSE] — a guild is selected and the centre panel is showing something a swipe would
 *   destroy: a thread-creation draft, a peeked thread (whose back gesture belongs to the peek), or a
 *   voice channel. The list is pinned shut.
 * * [LockState.OPEN] — no guild and no channel: there is nothing to show in the centre, so the list
 *   is pinned open and (see the caller) given the full portrait width.
 * * [LockState.UNLOCKED] — the ordinary chat surface, where the list swipes freely.
 */
class LeftPanelManager(
    private val storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
    private val storeGuildSelected: StoreGuildSelected = StoreStream.getGuildSelected(),
) {

    fun observeLockState(): Observable<LockState> = Observable.combineLatest(
        storeChannelsSelected.observeResolvedSelectedChannel(),
        storeGuildSelected.observeSelectedGuildId(),
    ) { selectedChannel, selectedGuildId ->
        lockState(selectedChannel, selectedGuildId)
    }.distinctUntilChanged()

    companion object {
        fun lockState(
            selectedChannel: StoreChannelsSelected.ResolvedSelectedChannel,
            selectedGuildId: Long,
        ): LockState {
            // Sentinel ids: `0` is `Unselected` and `-1` is `Unavailable`. The reference tests
            // exactly these two, so `ThreadDraft` (-3) and `Uninitialized` (-2) count as a channel.
            val hasChannel = selectedChannel.id != StoreChannelsSelected.ID_UNSELECTED &&
                selectedChannel.id != StoreChannelsSelected.ID_UNAVAILABLE
            val hasGuild = selectedGuildId != 0L
            val centerPanelOwnsTheGesture = when (selectedChannel) {
                is StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft -> true
                is StoreChannelsSelected.ResolvedSelectedChannel.Channel ->
                    selectedChannel.peekParent != null || selectedChannel.channel.isVoiceChannel()
                else -> false
            }
            return when {
                hasGuild && centerPanelOwnsTheGesture -> LockState.CLOSE
                !hasGuild && !hasChannel -> LockState.OPEN
                else -> LockState.UNLOCKED
            }
        }
    }
}
