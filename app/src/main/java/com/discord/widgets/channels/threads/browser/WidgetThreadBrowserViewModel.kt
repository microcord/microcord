package com.discord.widgets.channels.threads.browser

import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isAnnouncementChannel
import com.discord.api.channel.isGuildForumChannel
import com.discord.api.channel.isThread
import com.discord.api.channel.isVoiceChannel
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.ui
import rx.Observable

class WidgetThreadBrowserViewModel(
    val guildId: Long,
    val channelId: Long,
    stateSource: Observable<StoreState> = Observable.combineLatest(
        StoreStream.getChannels().observeChannelsForGuild(guildId),
        StoreStream.getGuilds().observeGuild(guildId),
        StoreStream.getPermissions().observePermissionsForChannel(channelId),
    ) { channels, guild, permissions -> StoreState(channels[channelId], guild, permissions) },
) : AppViewModel<WidgetThreadBrowserViewModel.ViewState>() {
    data class StoreState(val channel: Channel?, val guild: Guild?, val permissions: Long?)

    sealed class ViewState {
        data class Browser(
            val channelName: String?,
            val canCreateThread: Boolean,
            val canViewArchivedThreads: Boolean,
            val isForumChannel: Boolean,
        ) : ViewState()

        data class GoToChannel(val channel: Channel) : ViewState()
    }

    init {
        stateSource.ui(this).subscribe { state ->
            if (viewState is ViewState.GoToChannel) return@subscribe
            val channel = state.channel
            updateViewState(
                ViewState.Browser(
                    channel?.getDisplayName(),
                    canCreateThread(state.permissions, channel, state.guild),
                    canViewArchivedThreads(channel, state.permissions),
                    channel?.isGuildForumChannel() == true,
                ),
            )
        }
    }

    fun openThread(channel: Channel) = updateViewState(ViewState.GoToChannel(channel))

    companion object {
        internal fun canCreateThread(permissions: Long?, channel: Channel?, guild: Guild?): Boolean {
            if (permissions == null || channel == null || guild == null || channel.isThread() || channel.isVoiceChannel()) {
                return false
            }
            val canCreatePublic = PermissionUtils.can(Permission.CREATE_PUBLIC_THREADS, permissions)
            val canCreatePrivate = !channel.isAnnouncementChannel() &&
                PermissionUtils.can(Permission.CREATE_PRIVATE_THREADS, permissions)
            return canCreatePublic || canCreatePrivate
        }

        internal fun canViewArchivedThreads(channel: Channel?, permissions: Long?) =
            channel != null && PermissionUtils.hasAccess(channel, permissions) &&
                PermissionUtils.can(Permission.READ_MESSAGE_HISTORY, permissions)
    }
}
