package com.discord.widgets.settings.billing

import com.discord.app.AppViewModel
import com.discord.models.domain.ModelPaymentSource
import com.discord.models.domain.ModelSubscription
import com.discord.stores.StorePaymentSources
import com.discord.stores.StoreStream
import com.discord.stores.StoreSubscriptions
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import rx.Observable

class SettingsBillingViewModel(
    private val storePaymentSources: StorePaymentSources = StoreStream.getPaymentSources(),
    private val storeSubscriptions: StoreSubscriptions = StoreStream.getSubscriptions(),
    storeObservable: Observable<StoreState> = observeStores(storePaymentSources, storeSubscriptions),
) : AppViewModel<SettingsBillingViewModel.ViewState>(ViewState.Loading) {

    data class StoreState(
        val paymentSourceState: StorePaymentSources.PaymentSourcesState,
        val subscriptionsState: StoreSubscriptions.SubscriptionsState,
    )

    sealed class ViewState {
        object Loading : ViewState()
        object Failure : ViewState()
        data class Loaded(val paymentSourceItems: List<PaymentSourceAdapter.Item>) : ViewState()
    }

    init {
        fetchData()
        storeObservable.ui(this).appSubscribe(javaClass) { updateViewState(viewStateFor(it)) }
    }

    private fun fetchData() {
        storePaymentSources.fetchPaymentSources()
        storeSubscriptions.fetchSubscriptions()
    }

    companion object {
        private fun observeStores(
            paymentSources: StorePaymentSources,
            subscriptions: StoreSubscriptions,
        ): Observable<StoreState> = Observable.combineLatest(
            paymentSources.observePaymentSourcesState(),
            subscriptions.observeSubscriptions(),
            ::StoreState,
        )

        internal fun viewStateFor(state: StoreState): ViewState = when {
            state.paymentSourceState is StorePaymentSources.PaymentSourcesState.Failure ||
                state.subscriptionsState is StoreSubscriptions.SubscriptionsState.Failure -> ViewState.Failure
            state.paymentSourceState is StorePaymentSources.PaymentSourcesState.Loaded &&
                state.subscriptionsState is StoreSubscriptions.SubscriptionsState.Loaded -> ViewState.Loaded(
                generateListItems(
                    state.paymentSourceState.paymentSources,
                    state.subscriptionsState.premiumSubscription,
                ),
            )
            else -> ViewState.Loading
        }

        internal fun generateListItems(
            paymentSources: List<ModelPaymentSource>,
            premiumSubscription: ModelSubscription?,
        ): List<PaymentSourceAdapter.Item> = buildList {
            if (paymentSources.isEmpty()) return@buildList
            paymentSources.forEachIndexed { index, paymentSource ->
                when (index) {
                    0 -> add(PaymentSourceAdapter.PaymentSourceHeader(PaymentSourceAdapter.PaymentSourceHeader.Type.DEFAULT))
                    1 -> add(PaymentSourceAdapter.PaymentSourceHeader(PaymentSourceAdapter.PaymentSourceHeader.Type.OTHER))
                }
                add(
                    PaymentSourceAdapter.PaymentSourceItem(
                        paymentSource,
                        premiumSubscription?.let {
                            it.paymentSourceId == paymentSource.id && it.status != ModelSubscription.Status.CANCELED
                        } == true,
                    ),
                )
            }
            add(PaymentSourceAdapter.PaymentSourceAddItem())
        }
    }
}
