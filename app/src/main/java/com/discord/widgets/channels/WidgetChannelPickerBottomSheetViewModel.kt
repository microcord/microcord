package com.discord.widgets.channels

import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isAnnouncementChannel
import com.discord.api.channel.isCategory
import com.discord.app.AppViewModel
import com.discord.stores.StoreChannels
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

class WidgetChannelPickerBottomSheetViewModel(
    private val guildId: Long,
    private val selectedChannelId: Long,
    private val hideAnnouncementChannels: Boolean,
    storeStateObservable: Observable<StoreState> = observeStoreState(
        guildId,
        StoreStream.getChannels(),
        StoreStream.getPermissions(),
        hideAnnouncementChannels,
    ),
) : AppViewModel<WidgetChannelPickerBottomSheetViewModel.ViewState>(ViewState.Loading) {
    private var currentStoreState: StoreState? = null
    private var searchQuery = ""

    init {
        storeStateObservable
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(javaClass, onNext = ::handleStoreState)
    }

    fun updateSearchQuery(query: String) {
        if (query == searchQuery) return
        searchQuery = query
        currentStoreState?.let { updateViewState(generateViewState(it)) }
    }

    private fun handleStoreState(storeState: StoreState) {
        currentStoreState = storeState
        updateViewState(generateViewState(storeState))
    }

    private fun generateViewState(storeState: StoreState): ViewState = when (storeState) {
        StoreState.Invalid -> ViewState.Loading
        is StoreState.Loaded -> ViewState.Loaded(
            buildChannelItemList(storeState.channels, selectedChannelId, searchQuery),
        )
    }

    sealed class StoreState {
        object Invalid : StoreState()
        data class Loaded(val channels: List<Channel>) : StoreState()
    }

    sealed class ViewState {
        object Loading : ViewState()
        data class Loaded(val adapterItems: List<ChannelPickerAdapterItem>) : ViewState()
    }

    companion object {
        internal fun buildChannelItemList(
            channels: List<Channel>,
            selectedChannelId: Long,
            searchQuery: String,
        ): List<ChannelPickerAdapterItem> = buildList {
            add(ChannelPickerAdapterItem.CreateChannelItem)
            channels.forEach { channel ->
                if (searchQuery.isBlank() || channel.getDisplayName().contains(searchQuery)) {
                    add(ChannelPickerAdapterItem.ChannelItem(channel, channel.id == selectedChannelId))
                }
            }
        }

        internal fun filterChannels(
            channels: Collection<Channel>,
            permissions: Map<Long, Long>,
            hideAnnouncementChannels: Boolean,
        ): List<Channel> = channels.filter { channel ->
            !channel.isCategory() &&
                (!hideAnnouncementChannels || !channel.isAnnouncementChannel()) &&
                PermissionUtils.hasAccess(channel, permissions[channel.id])
        }

        private fun observeStoreState(
            guildId: Long,
            storeChannels: StoreChannels,
            storePermissions: StorePermissions,
            hideAnnouncementChannels: Boolean,
        ): Observable<StoreState> = Observable.combineLatest(
            storeChannels.observeChannelsForGuild(guildId),
            storePermissions.observeChannelPermissionsForGuild(guildId),
        ) { channels, permissions ->
            StoreState.Loaded(
                filterChannels(channels.values, permissions, hideAnnouncementChannels),
            ) as StoreState
        }
    }
}
