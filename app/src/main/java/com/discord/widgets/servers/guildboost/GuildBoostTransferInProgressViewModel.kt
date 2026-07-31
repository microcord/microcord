package com.discord.widgets.servers.guildboost

import androidx.annotation.MainThread
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelAppliedGuildBoost
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuildBoost
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.Subscription

class GuildBoostTransferInProgressViewModel(
    val slotId: Long,
    val boostId: Long,
    val previousGuildId: Long,
    val targetGuildId: Long,
    private val unsubscribeFromGuild: (Long, Long) -> Observable<Void> =
        { guildId, appliedBoostId -> RestAPI.api.unsubscribeToGuild(guildId, appliedBoostId) },
    private val subscribeToGuild: (Long, RestAPIParams.GuildBoosting) -> Observable<List<ModelAppliedGuildBoost>> =
        { guildId, params -> RestAPI.api.subscribeToGuild(guildId, params) },
    val storeGuildBoost: StoreGuildBoost = StoreStream.getGuildBoost(),
    storeObservable: Observable<StoreState> = observeStores(previousGuildId, targetGuildId),
) : AppViewModel<GuildBoostTransferInProgressViewModel.ViewState>(ViewState.Loading) {
    data class StoreState(
        val previousGuild: Guild?,
        val targetGuild: Guild?,
        val guildBoostState: StoreGuildBoost.State,
    )

    sealed class ViewState {
        object Loading : ViewState()
        object ErrorLoading : ViewState()
        object ErrorTransfer : ViewState()

        data class PreTransfer(
            val previousGuild: Guild,
            val targetGuild: Guild,
            val targetGuildSubscriptionCount: Int,
            val isTransferInProgress: Boolean,
        ) : ViewState()

        data class PostTransfer(
            val targetGuild: Guild,
            val targetGuildSubscriptionCount: Int,
        ) : ViewState()
    }

    private var guildBoostingSubscription: Subscription? = null

    init {
        storeObservable.computationLatest().ui(this).appSubscribe(javaClass, onNext = ::handleStoreState)
    }

    @MainThread
    internal fun handleStoreState(storeState: StoreState) {
        val previousGuild = storeState.previousGuild
        val targetGuild = storeState.targetGuild
        if (previousGuild == null || targetGuild == null) {
            updateViewState(ViewState.ErrorLoading)
            return
        }
        val count = (storeState.guildBoostState as? StoreGuildBoost.State.Loaded)
            ?.boostSlotMap
            ?.values
            ?.count { it.premiumGuildSubscription?.guildId == targetGuildId }
            ?: 0
        updateViewState(ViewState.PreTransfer(previousGuild, targetGuild, count, false))
    }

    @MainThread
    fun transferGuildBoost() {
        val current = requireViewState()
        if (current is ViewState.PreTransfer) {
            updateViewState(current.copy(isTransferInProgress = true))
        }
        unsubscribeFromGuild(previousGuildId, boostId)
            .flatMap {
                subscribeToGuild(targetGuildId, RestAPIParams.GuildBoosting(listOf(slotId)))
            }
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                javaClass,
                subscriptionHandler = { guildBoostingSubscription = it },
                errorHandler = { updateViewState(ViewState.ErrorTransfer) },
            ) {
                storeGuildBoost.fetchUserGuildBoostState()
                val state = requireViewState()
                if (state is ViewState.PreTransfer) {
                    updateViewState(ViewState.PostTransfer(state.targetGuild, state.targetGuildSubscriptionCount))
                }
            }
    }

    override fun onCleared() {
        guildBoostingSubscription?.unsubscribe()
        super.onCleared()
    }

    companion object {
        private fun observeStores(
            previousGuildId: Long,
            targetGuildId: Long,
        ): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(previousGuildId),
            StoreStream.getGuilds().observeGuild(targetGuildId),
            StoreStream.getGuildBoost().observeGuildBoostState(),
            ::StoreState,
        )
    }
}
