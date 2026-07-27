package com.discord.stores

import com.discord.models.domain.ModelPaymentSource
import com.discord.models.domain.PaymentSourceRaw
import com.discord.restapi.RestAPIInterface
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import rx.Observable

/**
 * The current user's saved payment sources.
 *
 * Like [StoreSubscriptions], the gateway event that invalidates this
 * (`USER_PAYMENT_SOURCES_UPDATE`) carries no payload, so the only reaction is a re-fetch.
 *
 * [ensureDefaultPaymentSource] is the one piece of real logic: the API may return a list in which
 * *no* source is flagged default (it happens after the previous default is deleted). Rather than
 * render a list with no primary card, the sources are sorted default-first and whichever ends up
 * first is forced to `default = true`. That is a display-level correction only — nothing is written
 * back to the server.
 */
class StorePaymentSources(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
) : StoreV2() {

    sealed class PaymentSourcesState {
        object Unfetched : PaymentSourcesState()
        object Loading : PaymentSourcesState()
        object Failure : PaymentSourcesState()
        data class Loaded(val paymentSources: List<ModelPaymentSource>) : PaymentSourcesState()
    }

    /** Raised when a payment source arrives in a shape this client cannot project. */
    class UnableToWrapException(cause: Throwable) : Exception(cause)

    private var paymentSourcesState: PaymentSourcesState = PaymentSourcesState.Unfetched
    private var paymentSourcesStateSnapshot: PaymentSourcesState = PaymentSourcesState.Unfetched

    fun getPaymentSourcesState(): PaymentSourcesState = paymentSourcesStateSnapshot

    fun observePaymentSourcesState(): Observable<PaymentSourcesState> =
        observationDeck.connectRx(this) { getPaymentSourcesState() }.distinctUntilChanged()

    fun fetchPaymentSources() = dispatcher.schedule {
        if (paymentSourcesState is PaymentSourcesState.Loading) return@schedule
        handlePaymentSourcesFetchStart()
        val api = restApi()
        if (api == null) {
            handlePaymentSourcesFetchFailure()
            return@schedule
        }
        api.getPaymentSources()
            .restSubscribeOn(false)
            .map(::ensureDefaultPaymentSource)
            .appSubscribe(
                StorePaymentSources::class.java,
                errorHandler = { dispatcher.schedule { handlePaymentSourcesFetchFailure() } },
            ) { paymentSources ->
                dispatcher.schedule { handlePaymentSourcesFetchSuccess(paymentSources) }
            }
    }

    /** Handler for `USER_PAYMENT_SOURCES_UPDATE`, which arrives with no `d` payload. */
    @StoreThread
    fun handleUserPaymentSourcesUpdate() = fetchPaymentSources()

    @StoreThread
    internal fun handlePaymentSourcesFetchStart() {
        paymentSourcesState = PaymentSourcesState.Loading
        markChanged()
    }

    @StoreThread
    internal fun handlePaymentSourcesFetchSuccess(paymentSources: List<ModelPaymentSource>) {
        paymentSourcesState = PaymentSourcesState.Loaded(paymentSources)
        markChanged()
    }

    @StoreThread
    internal fun handlePaymentSourcesFetchFailure() {
        paymentSourcesState = PaymentSourcesState.Failure
        markChanged()
    }

    @StoreThread
    fun handlePreLogout() {
        paymentSourcesState = PaymentSourcesState.Unfetched
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        val state = paymentSourcesState
        paymentSourcesStateSnapshot = when (state) {
            is PaymentSourcesState.Loaded -> state.copy(paymentSources = ArrayList(state.paymentSources))
            PaymentSourcesState.Failure,
            PaymentSourcesState.Loading,
            PaymentSourcesState.Unfetched,
            -> state
        }
    }

    internal fun ensureDefaultPaymentSource(
        rawPaymentSources: List<PaymentSourceRaw>,
    ): List<ModelPaymentSource> {
        if (rawPaymentSources.isEmpty()) return emptyList()
        val sorted = rawPaymentSources.sortedBy { if (it.default) 0 else 1 }.toMutableList()
        sorted[0] = sorted.first().copy(default = true)
        return sorted.map(::wrap)
    }

    /**
     * Reference `ModelPaymentSource.wrap`. It lives here rather than on the model because
     * `models/domain/ModelPaymentSource.kt` is owned by another slice and does not declare it yet;
     * move it there when that file is next touched.
     */
    private fun wrap(raw: PaymentSourceRaw): ModelPaymentSource = try {
        when (raw.type) {
            PAYMENT_SOURCE_TYPE_CARD -> ModelPaymentSource.ModelPaymentSourceCard(
                brand = checkNotNull(raw.brand),
                last4 = checkNotNull(raw.last_4),
                expiresMonth = raw.expiresMonth,
                expiresYear = raw.expiresYear,
                id = raw.id,
                invalid = raw.invalid,
                billingAddress = raw.billingAddress,
                default = raw.default,
            )

            PAYMENT_SOURCE_TYPE_PAYPAL -> ModelPaymentSource.ModelPaymentSourcePaypal(
                email = checkNotNull(raw.email),
                id = raw.id,
                invalid = raw.invalid,
                billingAddress = raw.billingAddress,
                default = raw.default,
            )

            else -> throw IllegalArgumentException("unsupported payment source type: ${raw.type}")
        }
    } catch (throwable: Throwable) {
        throw UnableToWrapException(throwable)
    }

    private companion object {
        const val PAYMENT_SOURCE_TYPE_CARD = 1
        const val PAYMENT_SOURCE_TYPE_PAYPAL = 2
    }
}
