package com.discord.stores

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.analytics.Traits
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import retrofit2.HttpException
import rx.Observable
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

/**
 * Client-side state machine for Play Billing purchases: what Play says the account owns, whether a
 * verification round-trip is in flight, and the one-shot outcome events the purchase sheet listens
 * for.
 *
 * Three separate pieces of state, because they answer different questions:
 *
 * - [State] — the purchases Play reported, split by SKU type, plus any [PendingDowngrade].
 * - [QueryState] — is a verification/downgrade call in flight right now. [processPurchases] waits
 *   for this to settle before handing purchases to the verifier, so two overlapping Play callbacks
 *   cannot double-verify.
 * - [Event] — a transient success/failure per Play product id. It is a [PublishSubject] on purpose:
 *   a purchase result must fire once, not replay to the next screen that subscribes.
 *
 * **No product ids appear in this file.** Everything is keyed by the ids Play itself returns, and
 * the SKU catalog (which is where Manticord-owned Play product configuration must be supplied)
 * lives in `utilities/billing/GooglePlayInAppSku.kt`.
 */
class StoreGooglePlayPurchases(
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
    private val dispatcher: Dispatcher,
    private val restApi: () -> RestAPIInterface? = { RestAPI.apiOrNull },
    private val clock: Clock = ClockFactory.get(),
    private val gson: Gson = Gson(),
    /**
     * Reference `AnalyticsTracker.paymentFlow{Started,Step,Completed,Failed}`. The reconstructed
     * `AnalyticsTracker` does not declare those four events yet, so this seam is inert by default;
     * the trait *cache* below is still real, because it is persisted and has to survive the process
     * death a Play checkout can cause.
     */
    private val analytics: PaymentFlowAnalytics = PaymentFlowAnalytics.None,
    /**
     * Reference `BillingUtils.verifyPurchases`. The billing client is not reconstructed yet, so
     * this defaults to a no-op; wire it to the verifier when `utilities/billing/BillingUtils`
     * lands.
     */
    private val verifyPurchases: (List<Purchase>) -> Unit = {},
) : StoreV2() {

    sealed class State {
        object Uninitialized : State()

        data class Loaded(
            val subscriptionPurchases: List<Purchase> = emptyList(),
            val iapPurchases: List<Purchase> = emptyList(),
            val pendingDowngrade: PendingDowngrade? = null,
        ) : State()
    }

    sealed class QueryState {
        object InProgress : QueryState()
        object NotInProgress : QueryState()
    }

    sealed class Event {
        /** [newSkuName] is the Play product id the flow was for. */
        data class PurchaseQueryFailure(val newSkuName: String) : Event()

        data class PurchaseQuerySuccess(
            val newSkuName: String,
            val skuId: Long? = null,
            val subscriptionPlanId: Long? = null,
            val giftCode: String? = null,
        ) : Event()
    }

    /** A Play purchase paired with what the Discord API said about it. */
    data class VerificationResult(
        val purchase: Purchase,
        val apiResponse: RestAPIParams.VerifyPurchaseResponse?,
    )

    /** The subset of `AnalyticsTracker` this store touches. */
    interface PaymentFlowAnalytics {
        fun paymentFlowStarted(trait: AnalyticsTrait)
        fun paymentFlowStep(trait: AnalyticsTrait, fromStep: String, toStep: String)
        fun paymentFlowCompleted(trait: AnalyticsTrait)
        fun paymentFlowFailed(trait: AnalyticsTrait)

        object None : PaymentFlowAnalytics {
            override fun paymentFlowStarted(trait: AnalyticsTrait) = Unit
            override fun paymentFlowStep(trait: AnalyticsTrait, fromStep: String, toStep: String) = Unit
            override fun paymentFlowCompleted(trait: AnalyticsTrait) = Unit
            override fun paymentFlowFailed(trait: AnalyticsTrait) = Unit
        }
    }

    /**
     * The analytics context of a payment flow, cached across the Play checkout.
     *
     * A Play purchase leaves the app and can outlive the process, so the traits that describe where
     * the flow started have to be persisted and picked up again when the result comes back — hence
     * the [timestamp] and the three-day expiry applied by [getOrClearAnalyticsTraits].
     */
    data class AnalyticsTrait(
        val skuId: Long,
        val timestamp: Long,
        val locationTrait: Traits.Location,
        val storeSkuTrait: Traits.StoreSku,
        val paymentTrait: Traits.Payment,
    )

    private var storeState: State = State.Uninitialized
    private var storeStateSnapshot: State = State.Uninitialized
    private var queryState: QueryState = QueryState.NotInProgress
    private var queryStateSnapshot: QueryState = QueryState.NotInProgress
    private val eventSubject = PublishSubject.create<Event>()

    private val subscriptionPurchases: List<Purchase>
        get() = (getState() as? State.Loaded)?.subscriptionPurchases.orEmpty()

    private val iapPurchases: List<Purchase>
        get() = (getState() as? State.Loaded)?.iapPurchases.orEmpty()

    private val pendingDowngrade: PendingDowngrade?
        get() = (getState() as? State.Loaded)?.pendingDowngrade

    fun getState(): State = storeStateSnapshot

    fun getQueryState(): QueryState = queryStateSnapshot

    fun observeState(): Observable<State> =
        observationDeck.connectRx(this) { getState() }.distinctUntilChanged()

    fun observeQueryState(): Observable<QueryState> =
        observationDeck.connectRx(this) { getQueryState() }.distinctUntilChanged()

    fun observeEvents(): Observable<Event> = eventSubject

    // -- Purchases ---------------------------------------------------------------------------

    /**
     * Accepts a Play query result and, once no verification is in flight, hands it to the verifier.
     *
     * The `takeSingleUntilTimeout` on [observeQueryState] is what serialises overlapping Play
     * callbacks: the second one waits for the first verification to finish instead of racing it.
     */
    fun processPurchases(purchases: List<Purchase>?, skuType: String) = dispatcher.schedule {
        handlePurchases(purchases, skuType)
        observeQueryState()
            .takeSingleUntilTimeout()
            .appSubscribe(StoreGooglePlayPurchases::class.java) { state ->
                if (state is QueryState.NotInProgress) verifyPurchases(purchases.orEmpty())
            }
    }

    /**
     * Stores [newPurchases] under [skuType].
     *
     * The early return is the reference's, and it matters: a Play callback that repeats a non-empty
     * list already held for that SKU type is ignored, so re-querying on every resume does not churn
     * the store. An *empty* list is always applied, because that is how a revoked purchase arrives.
     */
    @StoreThread
    internal fun handlePurchases(newPurchases: List<Purchase>?, skuType: String) {
        val current = when (skuType) {
            BillingClient.SkuType.SUBS -> subscriptionPurchases
            BillingClient.SkuType.INAPP -> iapPurchases
            else -> null
        }
        val unchanged = current != null &&
            newPurchases != null &&
            newPurchases.size == current.size &&
            newPurchases.containsAll(current) &&
            current.containsAll(newPurchases)
        if (newPurchases != null && newPurchases.isNotEmpty() && unchanged) return

        storeState = when (skuType) {
            BillingClient.SkuType.SUBS ->
                State.Loaded(newPurchases.orEmpty(), iapPurchases, pendingDowngrade)

            BillingClient.SkuType.INAPP ->
                State.Loaded(subscriptionPurchases, newPurchases.orEmpty(), pendingDowngrade)

            else -> State.Uninitialized
        }
        markChanged()
    }

    // -- Verification ------------------------------------------------------------------------

    fun onVerificationStart() = dispatcher.schedule {
        queryState = QueryState.InProgress
        markChanged()
    }

    fun onVerificationSuccess(verificationResult: VerificationResult) {
        verificationResult.purchase.skus.forEach { sku ->
            dispatcher.schedule {
                eventSubject.onNext(
                    Event.PurchaseQuerySuccess(
                        newSkuName = sku,
                        skuId = verificationResult.apiResponse?.verifiedSkuId,
                        subscriptionPlanId = verificationResult.apiResponse?.subscriptionPlanId,
                        giftCode = verificationResult.apiResponse?.giftCode,
                    ),
                )
                queryState = QueryState.NotInProgress
                markChanged()
            }
        }
    }

    fun onVerificationFailure(verificationResult: VerificationResult) = dispatcher.schedule {
        verificationResult.purchase.skus.forEach { sku ->
            eventSubject.onNext(Event.PurchaseQueryFailure(sku))
        }
        queryState = QueryState.NotInProgress
        markChanged()
    }

    // -- Downgrade ---------------------------------------------------------------------------

    fun updatePendingDowngrade(newPendingDowngrade: PendingDowngrade?) = dispatcher.schedule {
        storeState = State.Loaded(subscriptionPurchases, iapPurchases, newPendingDowngrade)
        markChanged()
    }

    fun downgradePurchase() = dispatcher.schedule {
        val downgrade = (storeState as? State.Loaded)?.pendingDowngrade ?: return@schedule
        queryState = QueryState.InProgress
        markChanged()
        doDowngrade(downgrade)
    }

    @StoreThread
    private fun doDowngrade(downgrade: PendingDowngrade) {
        val api = restApi()
        if (api == null) {
            handleDowngradeFailure(downgrade.newSkuName)
            return
        }
        api.downgradeSubscription(
            RestAPIParams.DowngradeSubscriptionBody(
                subscriptionId = downgrade.subscriptionId,
                purchaseToken = downgrade.purchaseToken,
                subscriptionSkuId = downgrade.newSkuName,
            ),
        )
            .subscribeOn(Schedulers.io())
            .retryTransient(::shouldRetryDowngrade)
            .appSubscribe(
                StoreGooglePlayPurchases::class.java,
                errorHandler = {
                    dispatcher.schedule { handleDowngradeFailure(downgrade.newSkuName) }
                },
            ) { dispatcher.schedule { handleDowngradeSuccess(downgrade.newSkuName) } }
    }

    @StoreThread
    internal fun handleDowngradeSuccess(newSkuName: String) {
        eventSubject.onNext(Event.PurchaseQuerySuccess(newSkuName))
        updatePendingDowngrade(null)
        queryState = QueryState.NotInProgress
        markChanged()
    }

    @StoreThread
    internal fun handleDowngradeFailure(newSkuName: String) {
        eventSubject.onNext(Event.PurchaseQueryFailure(newSkuName))
        updatePendingDowngrade(null)
        queryState = QueryState.NotInProgress
        markChanged()
    }

    /** A downgrade is only worth retrying for a transport failure or a 5xx. */
    internal fun shouldRetryDowngrade(throwable: Throwable): Boolean = when (throwable) {
        is TimeoutException -> false
        is HttpException -> throwable.code() in 500..599
        else -> throwable is IOException
    }

    // -- Gifting warning ---------------------------------------------------------------------

    fun hasSeenGiftingWarning(): Boolean = prefs.getBoolean(VIEWED_GIFTING_WARNING_KEY, false)

    fun markViewedGiftingWarning() =
        prefs.edit().putBoolean(VIEWED_GIFTING_WARNING_KEY, true).apply()

    // -- Payment flow analytics --------------------------------------------------------------

    fun trackPaymentFlowStarted(
        paymentGatewaySkuId: String,
        skuId: Long,
        locationTrait: Traits.Location,
        storeSkuTrait: Traits.StoreSku,
        paymentTrait: Traits.Payment,
    ) {
        val trait = AnalyticsTrait(
            skuId = skuId,
            timestamp = clock.currentTimeMillis(),
            locationTrait = locationTrait,
            storeSkuTrait = storeSkuTrait,
            paymentTrait = paymentTrait,
        )
        cacheAnalyticsTraits(cachedAnalyticsTraits().apply { put(paymentGatewaySkuId, trait) })
        analytics.paymentFlowStarted(trait)
    }

    fun trackPaymentFlowStep(paymentGatewaySkuId: String, fromStep: String, toStep: String) {
        val trait = getOrClearAnalyticsTraits(paymentGatewaySkuId) ?: return
        analytics.paymentFlowStep(trait, fromStep, toStep)
    }

    fun trackPaymentFlowCompleted(paymentGatewaySkuId: String) {
        val trait = getOrClearAnalyticsTraits(paymentGatewaySkuId) ?: return
        analytics.paymentFlowCompleted(trait)
        clearAnalyticsTraits(paymentGatewaySkuId)
    }

    fun trackPaymentFlowFailed(paymentGatewaySkuId: String) {
        val trait = getOrClearAnalyticsTraits(paymentGatewaySkuId) ?: return
        analytics.paymentFlowFailed(trait)
        clearAnalyticsTraits(paymentGatewaySkuId)
    }

    /** Expired traits are dropped rather than reported, so a flow abandoned days ago is silent. */
    private fun getOrClearAnalyticsTraits(paymentGatewaySkuId: String): AnalyticsTrait? {
        val trait = cachedAnalyticsTraits()[paymentGatewaySkuId] ?: return null
        if (clock.currentTimeMillis() - trait.timestamp > ANALYTICS_TRAIT_TTL_MS) {
            clearAnalyticsTraits(paymentGatewaySkuId)
            return null
        }
        return trait
    }

    private fun clearAnalyticsTraits(paymentGatewaySkuId: String) =
        cacheAnalyticsTraits(cachedAnalyticsTraits().apply { remove(paymentGatewaySkuId) })

    private fun cacheAnalyticsTraits(traits: Map<String, AnalyticsTrait>) =
        prefs.edit().putString(CACHE_KEY_PAYMENT_FLOW_ANALYTICS, gson.toJson(traits)).apply()

    private fun cachedAnalyticsTraits(): MutableMap<String, AnalyticsTrait> {
        val cached = prefs.getString(CACHE_KEY_PAYMENT_FLOW_ANALYTICS, null) ?: return LinkedHashMap()
        val type = object : TypeToken<MutableMap<String, AnalyticsTrait>>() {}.type
        return runCatching { gson.fromJson<MutableMap<String, AnalyticsTrait>>(cached, type) }
            .getOrNull() ?: LinkedHashMap()
    }

    // -- Lifecycle ---------------------------------------------------------------------------

    @StoreThread
    internal fun handlePreLogout() {
        storeState = State.Uninitialized
        queryState = QueryState.NotInProgress
        markChanged()
    }

    @StoreThread
    override fun snapshotData() {
        val state = storeState
        storeStateSnapshot = when (state) {
            is State.Loaded -> state.copy(
                subscriptionPurchases = ArrayList(state.subscriptionPurchases),
                iapPurchases = ArrayList(state.iapPurchases),
            )

            State.Uninitialized -> state
        }
        queryStateSnapshot = queryState
    }

    private companion object {
        const val CACHE_KEY_PAYMENT_FLOW_ANALYTICS = "CACHE_KEY_PAYMENT_FLOW_ANALYTICS"
        const val VIEWED_GIFTING_WARNING_KEY = "VIEWED_GIFTING_WARNING_DIALOG"

        /** Reference literal: three days. */
        const val ANALYTICS_TRAIT_TTL_MS = 259_200_000L
    }
}

/**
 * A subscription change the user has agreed to but that only takes effect at the end of the current
 * billing period.
 *
 * Play has no concept of a scheduled downgrade, so the client holds it and posts it to Discord's
 * billing API itself — that is why it lives in the store rather than in Play's purchase record.
 */
data class PendingDowngrade(
    val purchaseToken: String,
    val subscriptionId: String,
    val newSkuName: String,
) {
    override fun toString(): String =
        "PendingDowngrade(subscriptionId=$subscriptionId, newSkuName=$newSkuName, purchaseToken=<redacted>)"
}

/**
 * Reference `RetryWithDelay.restRetry(delay = 1000, maxHalfLives = 3, maxRetries = 3, predicate)`.
 *
 * Local because `com.discord.restapi.utils.RetryWithDelay` is not reconstructed; fold this into it
 * when it lands. The delay doubles from the second retry onward, capped at `maxHalfLives`
 * doublings.
 */
private fun <T> Observable<T>.retryTransient(predicate: (Throwable) -> Boolean): Observable<T> =
    retryWhen { failures ->
        var attempt = 0
        var halfLives = 0
        var delayMs = RETRY_INITIAL_DELAY_MS
        failures.flatMap { failure: Throwable ->
            if (attempt++ < RETRY_MAX_ATTEMPTS && predicate(failure)) {
                if (halfLives++ < RETRY_MAX_HALF_LIVES && halfLives > 1) delayMs *= 2
                Observable.timer(delayMs, TimeUnit.MILLISECONDS)
            } else {
                Observable.error<Long>(failure)
            }
        }
    }

private const val RETRY_INITIAL_DELAY_MS = 1_000L
private const val RETRY_MAX_ATTEMPTS = 3
private const val RETRY_MAX_HALF_LIVES = 3
