package com.discord.widgets.search

import com.discord.api.channel.Channel
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

/** What the search screen's chrome — the hint text and which pane is in front — renders from. */
class WidgetSearchViewModel(
    targetType: WidgetSearch.TargetType?,
    targetId: Long,
    searchStore: SearchStore = SearchStoreProvider.get(),
) : AppViewModel<WidgetSearchViewModel.ViewState>() {

    sealed interface ViewState {
        /** The target could not be resolved; the screen closes itself. */
        data object Invalid : ViewState

        data class Loaded(
            val guild: Guild?,
            val channel: Channel?,
            val displayState: SearchStore.DisplayState,
            val isQueryValid: Boolean,
        ) : ViewState
    }

    init {
        val guild: Observable<Guild?> = if (targetType == WidgetSearch.TargetType.GUILD) {
            StoreStream.getGuilds().observeGuild(targetId)
        } else {
            Observable.just<Guild?>(null)
        }
        val channel: Observable<Channel?> = if (targetType == WidgetSearch.TargetType.CHANNEL) {
            StoreStream.getChannels().observeChannel(targetId)
        } else {
            Observable.just<Channel?>(null)
        }

        Observable
            .combineLatest(
                guild,
                channel,
                searchStore.getDisplayState(),
                searchStore.storeSearchInput.isInputValid,
            ) { resolvedGuild, resolvedChannel, displayState, isQueryValid ->
                if (targetType == null || (resolvedGuild == null && resolvedChannel == null)) {
                    ViewState.Invalid
                } else {
                    ViewState.Loaded(resolvedGuild, resolvedChannel, displayState, isQueryValid)
                }
            }
            .computationLatest()
            .distinctUntilChanged()
            .ui(this)
            .subscribe(::updateViewState)
    }
}
