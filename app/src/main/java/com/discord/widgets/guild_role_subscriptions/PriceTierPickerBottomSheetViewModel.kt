package com.discord.widgets.guild_role_subscriptions

import com.discord.app.AppViewModel
import com.discord.stores.StoreGuildRoleSubscriptions
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * Serves the catalogue of price points a creator may charge.
 *
 * The catalogue is a fixed server-side list, so this view model only asks the store to fetch it
 * once and mirrors the store's three states straight through.
 */
class PriceTierPickerBottomSheetViewModel(
    storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions = StoreStream.getGuildRoleSubscriptions(),
    storeObservable: Observable<StoreState> = observeStoreState(
        ObservationDeckProvider.get(),
        storeGuildRoleSubscriptions,
    ),
) : AppViewModel<PriceTierPickerBottomSheetViewModel.ViewState>() {

    data class StoreState(val priceTierState: StoreGuildRoleSubscriptions.PriceTierState)

    sealed class ViewState {
        object Loading : ViewState()
        object Failed : ViewState()
        data class Loaded(val priceTiers: List<Int>) : ViewState()
    }

    init {
        storeGuildRoleSubscriptions.fetchPriceTiersIfNonExisting()
        storeObservable
            .computationLatest()
            .ui(this)
            .appSubscribe(PriceTierPickerBottomSheetViewModel::class.java) { handleStoreState(it) }
    }

    private fun handleStoreState(storeState: StoreState) {
        when (val priceTierState = storeState.priceTierState) {
            is StoreGuildRoleSubscriptions.PriceTierState.Loading -> updateViewState(ViewState.Loading)
            is StoreGuildRoleSubscriptions.PriceTierState.Failed -> updateViewState(ViewState.Failed)
            is StoreGuildRoleSubscriptions.PriceTierState.Loaded ->
                updateViewState(ViewState.Loaded(priceTierState.priceTiers))
            // Uninitialized: the fetch started in `init` has not reported yet, so the sheet keeps
            // whatever it is showing rather than flashing an empty list.
            else -> Unit
        }
    }

    companion object {
        private fun observeStoreState(
            observationDeck: ObservationDeck,
            storeGuildRoleSubscriptions: StoreGuildRoleSubscriptions,
        ): Observable<StoreState> = observationDeck.connectRx(storeGuildRoleSubscriptions) {
            StoreState(storeGuildRoleSubscriptions.getPriceTiersState())
        }
    }
}
