package com.discord.widgets.guilds.invite

import com.discord.app.AppViewModel
import com.discord.models.domain.ModelInvite
import com.discord.stores.StoreInstantInvites
import com.discord.stores.StoreInviteSettings
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

class GuildInviteViewModel(
    val inviteCode: StoreInviteSettings.InviteCode?,
    private val instantInvitesStore: StoreInstantInvites = StoreStream.getInstantInvites(),
    val storeStateObservable: Observable<StoreState> = observeStoreState(instantInvitesStore, inviteCode),
) : AppViewModel<GuildInviteViewModel.ViewState>(ViewState.Uninitialized) {

    sealed class StoreState {
        object Loading : StoreState()
        object Invalid : StoreState()
        data class Loaded(val invite: ModelInvite) : StoreState()
    }

    sealed class ViewState {
        object Uninitialized : ViewState()
        object Loading : ViewState()
        object Invalid : ViewState()
        data class Loaded(val invite: ModelInvite) : ViewState()
    }

    var inviteResolved = false

    init {
        storeStateObservable.computationLatest().ui(this).appSubscribe(GuildInviteViewModel::class.java) {
            updateViewState(
                when (it) {
                    StoreState.Loading -> ViewState.Loading
                    StoreState.Invalid -> ViewState.Invalid
                    is StoreState.Loaded -> ViewState.Loaded(it.invite)
                },
            )
        }
    }

    fun fetchInviteIfNotLoaded() {
        val code = inviteCode ?: return
        instantInvitesStore.fetchInviteIfNotLoaded(
            code.inviteCode,
            code.eventId,
            code.targetChannelId,
            code.targetMessageId,
        )
    }

    companion object {
        fun observeStoreState(
            instantInvitesStore: StoreInstantInvites,
            inviteCode: StoreInviteSettings.InviteCode?,
        ): Observable<StoreState> {
            if (inviteCode == null) return Observable.just(StoreState.Invalid)
            return instantInvitesStore
                .observeInvite(ModelInvite.getInviteStoreKey(inviteCode.inviteCode, inviteCode.eventId))
                .map {
                    when (it) {
                        StoreInstantInvites.InviteState.Loading -> StoreState.Loading
                        is StoreInstantInvites.InviteState.Resolved -> StoreState.Loaded(it.invite)
                        StoreInstantInvites.InviteState.Invalid,
                        StoreInstantInvites.InviteState.LoadFailed,
                        -> StoreState.Invalid
                    }
                }
        }
    }
}
