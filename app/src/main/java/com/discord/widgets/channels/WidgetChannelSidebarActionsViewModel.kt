package com.discord.widgets.channels

import com.discord.api.channel.Channel
import com.discord.api.channel.isDirectory
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isThread
import com.discord.api.user.NsfwAllowance
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelNotificationSettings
import com.discord.panels.PanelState
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreGuildsNsfw
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserGuildSettings
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * Drives [WidgetChannelSidebarActions], the row of Search / Threads / Notifications / Pins /
 * Settings buttons at the top of the right-hand panel.
 *
 * Private channels get a different row entirely (call, video, notifications, search), so the view
 * state is a three-way split rather than one model with unused fields.
 *
 * Two subtleties are carried through from the reference:
 *
 * - **Pins are disabled on an age-gated NSFW channel.** A channel is pin-locked when it is NSFW and
 *   either the guild's NSFW gate has not been agreed to or the viewer's account is not allowed NSFW
 *   content — otherwise the pin list would leak content past the gate.
 * - **The subscription stops when the panel is closed.** Like the topic strip, this takes a single
 *   emission while the right panel is shut so a busy guild does not keep the row re-rendering out
 *   of sight.
 */
class WidgetChannelSidebarActionsViewModel(
    storeStateObservable: Observable<StoreState> = observeStoreState(),
) : AppViewModel<WidgetChannelSidebarActionsViewModel.ViewState>(ViewState.Uninitialized) {

    init {
        storeStateObservable
            .ui(this)
            .appSubscribe(WidgetChannelSidebarActionsViewModel::class.java) { handleStoreState(it) }
    }

    sealed class StoreState {
        object ChannelNotFound : StoreState()

        data class ChannelFound(
            val channel: Channel,
            /** The thread's or forum post's parent, used to tell a forum post from a plain thread. */
            val parentChannel: Channel?,
            val guildNotificationSettings: ModelNotificationSettings?,
            val disablePins: Boolean,
        ) : StoreState()
    }

    sealed class ViewState {
        /** Before the first store emission, and whenever no channel is selected. */
        object Uninitialized : ViewState()

        data class Guild(
            val channelId: Long,
            val guildId: Long,
            val isMuted: Boolean,
            /**
             * Always false: the reference never computes it either, so the pin icon never shows its
             * unread variant.
             */
            val hasUnreadPins: Boolean,
            val disablePins: Boolean,
            val isThread: Boolean,
            val isGuildForumPost: Boolean,
            val isGuildForumChannel: Boolean,
            /** Directory channels have no sidebar actions at all. */
            val shouldHideChannelSidebar: Boolean,
        ) : ViewState()

        data class Private(
            val channelId: Long,
            val isMuted: Boolean,
        ) : ViewState()
    }

    private fun handleStoreState(storeState: StoreState) = when (storeState) {
        StoreState.ChannelNotFound -> updateViewState(ViewState.Uninitialized)

        is StoreState.ChannelFound -> {
            val channel = storeState.channel
            val override = storeState.guildNotificationSettings?.getChannelOverride(channel.id)
            if (channel.isPrivate()) {
                updateViewState(ViewState.Private(channel.id, override?.isMuted == true))
            } else {
                updateViewState(
                    ViewState.Guild(
                        channelId = channel.id,
                        guildId = channel.guildId,
                        isMuted = override?.isMuted == true,
                        hasUnreadPins = false,
                        disablePins = storeState.disablePins,
                        isThread = channel.isThread(),
                        isGuildForumPost = storeState.parentChannel?.isGuildForumChannel() == true,
                        isGuildForumChannel = channel.isGuildForumChannel(),
                        shouldHideChannelSidebar = channel.isDirectory(),
                    ),
                )
            }
        }
    }

    companion object {
        fun observeStoreState(
            storeNavigation: StoreNavigation = StoreStream.getNavigation(),
            storeChannels: StoreChannels = StoreStream.getChannels(),
            storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
            storeUserGuildSettings: StoreUserGuildSettings = StoreStream.getUserGuildSettings(),
            storeUser: StoreUser = StoreStream.getUsers(),
            /**
             * `StoreStream` publishes no accessor for [StoreGuildsNsfw] yet, so the gate defaults to
             * "agreed". The only effect is that pins stay enabled on an NSFW channel whose gate the
             * viewer has not accepted; the viewer's own account-level NSFW allowance still applies.
             */
            storeGuildsNsfw: StoreGuildsNsfw? = null,
        ): Observable<StoreState> = storeNavigation.observeRightPanelState().switchMap { panelState ->
            val storeState = storeChannelsSelected.observeSelectedChannel().switchMap { channel ->
                if (channel == null) {
                    Observable.just<StoreState>(StoreState.ChannelNotFound)
                } else {
                    Observable.combineLatest(
                        storeUserGuildSettings.observeGuildSettings(),
                        storeUser.observeMe(),
                        storeChannels.observeChannel(channel.parentId),
                    ) { guildSettings, meUser, parentChannel ->
                        val gateAgreed = storeGuildsNsfw?.isGuildNsfwGateAgreed(channel.guildId) ?: true
                        StoreState.ChannelFound(
                            channel = channel,
                            parentChannel = parentChannel,
                            guildNotificationSettings = guildSettings[channel.guildId],
                            disablePins = channel.nsfw &&
                                (!gateAgreed || meUser.nsfwAllowance == NsfwAllowance.DISALLOWED),
                        )
                    }
                }
            }
            if (panelState == PanelState.Open) storeState else storeState.take(1)
        }
    }
}
