package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.networking.NetworkMonitor
import com.discord.utilities.time.Clock
import kotlin.math.roundToInt
import rx.Observable
import rx.Subscription

/**
 * Projects device connectivity plus gateway session state into the four states the connection
 * banner renders.
 *
 * The emitted [DelayedState] carries the delay the UI should wait before *showing* the state. That
 * lets a short reconnect resolve silently while a genuinely stuck connection surfaces after the
 * delay. CONNECTING gets the long one: it covers the whole of startup, a network blip, and a channel
 * fetching its messages — which every guild switch does — and in all three cases there is cached UI
 * on screen, so nothing is gained by interrupting it. Offline is the short one, since the user
 * cannot mistake it for progress. The delay is recomputed on every update against how long the
 * current state has already been active, so a state that has been pending a while surfaces sooner.
 */
class StoreConnectivity(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    enum class State { ONLINE, OFFLINE, OFFLINE_AIRPLANE_MODE, CONNECTING }

    data class DelayedState(val state: State, val delay: Long = DELAY_NOMINAL)

    private var delayedState: DelayedState by MarkChangedDelegate(DelayedState(State.CONNECTING, DELAY_EXTENDED))

    private var deviceNetworkState: NetworkMonitor.State = NetworkMonitor.State.OFFLINE
    private var isReadyPayloadReceived: Boolean = false
    private var channelMessagesLoading: Boolean = false
    private var stateTriggeredDelay: Long = DELAY_EXTENDED
    private var stateTriggeredTimeMillis: Long = clock.currentTimeMillis()

    /** Nothing surfaces before this; see [handleBackgrounded]. */
    private var earliestVisibleTimeMillis: Long = 0L

    private var networkSubscription: Subscription? = null
    private var messagesLoadingSubscription: Subscription? = null

    fun init(networkMonitor: NetworkMonitor, channelMessagesLoading: Observable<Boolean>) {
        networkSubscription?.unsubscribe()
        networkSubscription = networkMonitor.observeState().subscribe(
            { networkState -> dispatcher.schedule { handleDeviceNetworkStateUpdated(networkState) } },
            {},
        )
        messagesLoadingSubscription?.unsubscribe()
        messagesLoadingSubscription = channelMessagesLoading.subscribe(
            { loading -> dispatcher.schedule { handleChannelMessagesLoading(loading) } },
            {},
        )
    }

    fun isConnected(): Boolean = delayedState.state == State.ONLINE

    fun getState(): DelayedState = delayedState

    fun observeState(): Observable<DelayedState> =
        observationDeck.connectRx(this, generator = { delayedState }).distinctUntilChanged()

    @StoreThread
    fun handleConnected(connected: Boolean) {
        if (connected) return
        isReadyPayloadReceived = false
        updateConnectivityState()
    }

    @StoreThread
    fun handleConnectionReady(connectionReady: Boolean) {
        isReadyPayloadReceived = connectionReady
        updateConnectivityState()
    }

    /**
     * The delay budget measures how long the user has been kept waiting, and a backgrounded user is
     * not waiting: time away must not spend it. Coming forward then buys a further
     * [DELAY_FOREGROUND_COOLOFF], so a connection that has to re-establish itself gets to do so
     * before the user is told anything is wrong.
     */
    @StoreThread
    fun handleBackgrounded(backgrounded: Boolean) {
        stateTriggeredTimeMillis = clock.currentTimeMillis()
        if (!backgrounded) {
            earliestVisibleTimeMillis = clock.currentTimeMillis() + DELAY_FOREGROUND_COOLOFF
        }
        updateConnectivityState()
    }

    @StoreThread
    fun handleDeviceNetworkStateUpdated(deviceNetworkState: NetworkMonitor.State) {
        this.deviceNetworkState = deviceNetworkState
        updateConnectivityState()
    }

    @StoreThread
    fun handleChannelMessagesLoading(channelMessagesLoading: Boolean) {
        this.channelMessagesLoading = channelMessagesLoading
        updateConnectivityState()
    }

    @StoreThread
    private fun updateConnectivityState() {
        val nextState = when {
            deviceNetworkState == NetworkMonitor.State.OFFLINE -> State.OFFLINE
            deviceNetworkState == NetworkMonitor.State.OFFLINE_AIRPLANE_MODE -> State.OFFLINE_AIRPLANE_MODE
            isReadyPayloadReceived && !channelMessagesLoading -> State.ONLINE
            else -> State.CONNECTING
        }
        if (delayedState.state != nextState) {
            stateTriggeredTimeMillis = clock.currentTimeMillis()
            stateTriggeredDelay = if (nextState == State.CONNECTING) DELAY_EXTENDED else DELAY_NOMINAL
        }
        delayedState = DelayedState(nextState, getStateDelay(stateTriggeredDelay))
    }

    private fun getStateActiveMillis(): Long = clock.currentTimeMillis() - stateTriggeredTimeMillis

    /** Remaining delay, quantised to [DELAY_INTERVAL] so equal states de-duplicate. */
    private fun getStateDelay(defaultDelay: Long): Long {
        val remaining = maxOf(
            defaultDelay - getStateActiveMillis(),
            earliestVisibleTimeMillis - clock.currentTimeMillis(),
        )
        return ((remaining.toFloat() / DELAY_INTERVAL).roundToInt().toLong() * DELAY_INTERVAL)
            .coerceAtLeast(0L)
    }

    fun reset() {
        networkSubscription?.unsubscribe()
        networkSubscription = null
        messagesLoadingSubscription?.unsubscribe()
        messagesLoadingSubscription = null
    }

    companion object {
        const val DELAY_INTERVAL = 100L
        const val DELAY_NOMINAL = 1_000L
        const val DELAY_EXTENDED = 10_000L
        const val DELAY_FOREGROUND_COOLOFF = 5_000L
    }
}
