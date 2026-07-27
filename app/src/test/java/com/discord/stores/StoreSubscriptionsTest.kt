package com.discord.stores

import com.discord.models.domain.ModelSubscription
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreSubscriptions.SubscriptionsState
import com.discord.stores.updates.ObservationDeck
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import rx.Observable
import rx.plugins.RxJavaHooks
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject

class StoreSubscriptionsTest {
    private val deck = ObservationDeck()
    private val dispatcher = Dispatcher(storeTestScheduler("StoreSubscriptionsTest"))
    private val responses = ArrayDeque<Observable<List<ModelSubscription>>>()
    private var requests = 0
    private val store = StoreSubscriptions(deck, dispatcher) { restApi() }

    @Before
    fun setUp() {
        RxJavaHooks.setOnIOScheduler { Schedulers.immediate() }
        dispatcher.registerDispatchHandlers(StoreV2DispatchHandler(listOf(store), deck))
    }

    @After
    fun tearDown() = RxJavaHooks.reset()

    @Test
    fun fetchGatewayRefreshSnapshotsAndLogoutMatchReferenceStateMachine() {
        val firstResponse = PublishSubject.create<List<ModelSubscription>>()
        val refreshResponse = PublishSubject.create<List<ModelSubscription>>()
        responses += firstResponse
        responses += refreshResponse
        val observed = mutableListOf<SubscriptionsState>()
        store.observeSubscriptions().subscribe(observed::add)

        assertEquals(listOf(SubscriptionsState.Unfetched), observed)
        assertFalse(store.hasFetchedSubscriptions())

        store.fetchSubscriptions()
        store.fetchSubscriptions()
        assertEquals(1, requests)
        assertTrue(store.isFetchingSubscriptions())

        val mutableResponse = mutableListOf(subscription("premium", type = 1), subscription("guild", type = 2))
        firstResponse.onNext(mutableResponse)
        val loaded = store.getSubscriptions() as SubscriptionsState.Loaded
        mutableResponse.clear()

        assertEquals(listOf("premium", "guild"), loaded.subscriptions.map { it.id })
        assertEquals("premium", loaded.premiumSubscription?.id)
        assertTrue(store.hasFetchedSubscriptions())

        dispatcher.schedule { store.handleUserSubscriptionsUpdate() }
        assertEquals(2, requests)
        assertTrue(store.isFetchingSubscriptions())
        refreshResponse.onNext(emptyList())
        assertNull((store.getSubscriptions() as SubscriptionsState.Loaded).premiumSubscription)

        dispatcher.schedule { store.handlePreLogout() }
        assertEquals(SubscriptionsState.Unfetched, store.getSubscriptions())
    }

    @Test
    fun failedFetchCanBeRetried() {
        responses += Observable.error(IllegalStateException("failed"))
        responses += Observable.just(emptyList())

        store.fetchSubscriptions()
        assertEquals(SubscriptionsState.Failure, store.getSubscriptions())

        store.fetchSubscriptions()
        assertEquals(2, requests)
        assertEquals(SubscriptionsState.Loaded(emptyList()), store.getSubscriptions())
    }

    private fun restApi(): RestAPIInterface = Proxy.newProxyInstance(
        RestAPIInterface::class.java.classLoader,
        arrayOf(RestAPIInterface::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getSubscriptions" -> {
                requests++
                responses.removeFirst()
            }
            else -> error("Unexpected REST call: ${method.name}")
        }
    } as RestAPIInterface

    private fun subscription(id: String, type: Int) = ModelSubscription(
        id = id,
        createdAt = "2021-01-01T00:00:00+00:00",
        canceledAt = null,
        currentPeriodStart = "2021-01-01T00:00:00+00:00",
        currentPeriodEnd = "2021-02-01T00:00:00+00:00",
        paymentSourceId = null,
        paymentGateway = null,
        trialId = null,
        trialEndsAt = null,
        renewalMutations = null,
        items = emptyList(),
        paymentGatewayPlanId = null,
        statusValue = 1,
        typeValue = type,
    )
}
