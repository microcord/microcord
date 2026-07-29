package com.discord.widgets.guilds.join

import com.discord.api.guild.welcome.GuildWelcomeChannel
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.stores.StoreGuildWelcomeScreens
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.rx.ui
import rx.Observable

class WidgetGuildWelcomeSheetViewModel(
    private val guildId: Long,
    storeObservable: Observable<StoreState> = observeStores(guildId),
) : AppViewModel<WidgetGuildWelcomeSheetViewModel.ViewState>(ViewState.Loading) {
    init {
        storeObservable.ui(this).subscribe { updateViewState(reduce(it)) }
        StoreStream.getGuildWelcomeScreens().fetchIfNonexisting(guildId)
        StoreStream.getGuildWelcomeScreens().markWelcomeScreenShown(guildId)
    }

    fun onClickChannel(guildId: Long, channelId: Long, index: Int, welcomeChannels: List<GuildWelcomeChannel>) {
        if (index !in welcomeChannels.indices) return
        ChannelSelector.getInstance().selectChannel(guildId, channelId)
    }

    data class StoreState(
        val guild: Guild?,
        val guildWelcomeScreen: StoreGuildWelcomeScreens.State?,
    )

    sealed class ViewState {
        object Invalid : ViewState()
        object Loading : ViewState()
        data class Loaded(
            val guildId: Long,
            val guildName: String,
            val guildShortName: String,
            val guildIcon: String?,
            val guildDescription: String?,
            val welcomeChannelsData: List<GuildWelcomeChannel>?,
        ) : ViewState()
    }

    companion object {
        fun observeStores(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getGuildWelcomeScreens().observeGuildWelcomeScreen(guildId),
        ) { guild, welcome -> StoreState(guild, welcome) }

        internal fun reduce(state: StoreState): ViewState {
            val guild = state.guild
            val welcome = state.guildWelcomeScreen
            val loaded = welcome as? StoreGuildWelcomeScreens.State.Loaded
            return when {
                guild != null && welcome === StoreGuildWelcomeScreens.State.Failure -> ViewState.Invalid
                welcome === StoreGuildWelcomeScreens.State.Fetching -> ViewState.Loading
                guild != null -> ViewState.Loaded(
                    guild.id,
                    guild.name,
                    guild.shortName,
                    guild.icon,
                    loaded?.data?.description,
                    loaded?.data?.welcomeChannels,
                )
                loaded?.data == null -> ViewState.Invalid
                else -> ViewState.Loading
            }
        }
    }
}
