package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelInvite
import com.discord.stores.StoreInviteSettings
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.PublishSubject

class GuildInviteSettingsViewModel(
    private val channelId: Long?,
    val guildId: Long,
    private val storeInviteSettings: StoreInviteSettings = InviteSettingsSource.store,
    private val targetChannelSelector: TargetChannelSelector = TargetChannelSelector(),
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId, storeInviteSettings),
) : AppViewModel<GuildInviteSettingsViewModel.ViewState>() {

    data class StoreState(
        val inviteSettings: ModelInvite.Settings,
        val invitableChannels: Map<Long, Channel>,
    )

    data class ViewState(
        val invitableChannels: List<Channel>,
        val inviteSettings: ModelInvite.Settings,
        val targetChannel: Channel?,
    )

    sealed class Event {
        data class InviteCreationSuccess(val invite: GuildInvite) : Event()
        object InviteCreationFailure : Event()
    }

    private val eventSubject = PublishSubject.create<Event>()
    private var pendingSettings: ModelInvite.Settings? = null
    private var targetChannel: Channel? = null

    init {
        storeStateObservable.computationLatest().ui(this).appSubscribe(GuildInviteSettingsViewModel::class.java) {
            val settings = pendingSettings ?: it.inviteSettings.also { initial -> pendingSettings = initial }
            val channel = targetChannel
                ?: targetChannelSelector.getTargetChannel(it.invitableChannels, channelId).also { selected -> targetChannel = selected }
            updateViewState(ViewState(it.invitableChannels.values.toList(), settings, channel))
        }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun selectChannel(channel: Channel?) {
        val state = viewState ?: return
        if (channel == null) return
        targetChannel = channel
        updateViewState(state.copy(targetChannel = channel))
    }

    fun updatePendingInviteSettings(settings: ModelInvite.Settings) {
        val state = viewState ?: return
        pendingSettings = settings
        updateViewState(state.copy(inviteSettings = settings))
    }

    fun saveInviteSettings() {
        val settings = pendingSettings ?: return
        val channel = targetChannel ?: return
        storeInviteSettings.setInviteSettings(settings)
        storeInviteSettings.generateInvite(channel.id)
            .ui(this)
            .appSubscribe(
                GuildInviteSettingsViewModel::class.java,
                errorHandler = { eventSubject.onNext(Event.InviteCreationFailure) },
            ) { eventSubject.onNext(Event.InviteCreationSuccess(GuildInvite.createFromModelInvite(it))) }
    }

    companion object {
        fun observeStoreState(
            guildId: Long,
            storeInviteSettings: StoreInviteSettings,
        ): Observable<StoreState> = Observable.combineLatest(
            storeInviteSettings.getInviteSettings(),
            storeInviteSettings.getInvitableChannels(guildId),
            ::StoreState,
        )
    }
}
