package com.discord.widgets.servers.guildboost

import androidx.annotation.MainThread
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelAppliedGuildBoost
import com.discord.models.domain.ModelGuildBoostSlot
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

class GuildBoostInProgressViewModel(
    guildId: Long,
    val storeGuildBoost: StoreGuildBoost = StoreStream.getGuildBoost(),
    private val subscribeToGuild: (Long, RestAPIParams.GuildBoosting) -> Observable<List<ModelAppliedGuildBoost>> =
        { targetGuildId, params -> RestAPI.api.subscribeToGuild(targetGuildId, params) },
    storeObservable: Observable<StoreState> = observeStores(guildId),
) : AppViewModel<GuildBoostInProgressViewModel.ViewState>(
    ViewState.Uninitialized(GuildBoostState.NOT_IN_PROGRESS),
) {
    enum class GuildBoostState { NOT_IN_PROGRESS, CALL_IN_PROGRESS, COMPLETED, ERROR }

    data class StoreState(
        val guild: Guild?,
        val guildBoostState: StoreGuildBoost.State,
    )

    sealed class ViewState(open val guildBoostState: GuildBoostState) {
        data class Uninitialized(
            override val guildBoostState: GuildBoostState,
        ) : ViewState(guildBoostState)

        data class Loaded(
            override val guildBoostState: GuildBoostState,
            val guild: Guild?,
            val subscriptionCount: Int,
            val canShowConfirmationDialog: Boolean = false,
        ) : ViewState(guildBoostState)
    }

    private var guildBoostSubscription: Subscription? = null

    init {
        storeObservable.computationLatest().ui(this).appSubscribe(javaClass, onNext = ::handleStoreState)
    }

    @MainThread
    internal fun handleStoreState(storeState: StoreState) {
        val count = (storeState.guildBoostState as? StoreGuildBoost.State.Loaded)
            ?.boostSlotMap
            ?.values
            ?.let { countBoostsForGuild(it, storeState.guild?.id ?: 0L) }
            ?: 0
        updateViewState(
            ViewState.Loaded(requireViewState().guildBoostState, storeState.guild, count),
        )
    }

    @MainThread
    fun subscribeToGuildBoost(guildId: Long, slotId: Long) {
        updateBoostState(GuildBoostState.CALL_IN_PROGRESS)
        subscribeToGuild(guildId, RestAPIParams.GuildBoosting(listOf(slotId)))
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(
                StoreGuildBoost::class.java,
                subscriptionHandler = { guildBoostSubscription = it },
                errorHandler = { updateBoostState(GuildBoostState.ERROR) },
            ) {
                storeGuildBoost.fetchUserGuildBoostState()
                updateBoostState(GuildBoostState.COMPLETED, canShowConfirmationDialog = true)
            }
    }

    @MainThread
    private fun updateBoostState(
        state: GuildBoostState,
        canShowConfirmationDialog: Boolean = false,
    ) {
        updateViewState(
            when (val current = requireViewState()) {
                is ViewState.Uninitialized -> ViewState.Uninitialized(state)
                is ViewState.Loaded -> current.copy(
                    guildBoostState = state,
                    canShowConfirmationDialog = canShowConfirmationDialog,
                )
            },
        )
    }

    override fun onCleared() {
        super.onCleared()
        guildBoostSubscription?.unsubscribe()
    }

    companion object {
        private fun observeStores(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getGuildBoost().observeGuildBoostState(),
            ::StoreState,
        )

        internal fun countBoostsForGuild(
            slots: Collection<ModelGuildBoostSlot>,
            guildId: Long,
        ): Int = slots.count { it.premiumGuildSubscription?.guildId == guildId }
    }
}
