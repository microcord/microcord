package com.discord.stores

import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.networking.NetworkMonitor
import com.discord.utilities.time.Clock
import kotlin.math.roundToLong
import rx.Observable
import rx.Subscription

/**
 * Projects device connectivity plus gateway session state into the four states the connection
 * banner renders.
 *
 * The emitted [DelayedState] carries the delay the UI should wait before *showing* the state. That
 * lets a short reconnect resolve silently while a genuinely stuck connection surfaces after
 * [DELAY_NOMINAL] (or [DELAY_EXTENDED] for a cold connect). The delay is recomputed on every
 * update against how long the current state has already been active, so a state that has been
 * pending for a while surfaces sooner.
 */
class StoreConnectivity(
    private val dispatcher: Dispatcher,
    private val clock: Clock,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    enum class State { ONLINE, OFFLINE, OFFLINE_AIRPLANE_MODE, CONNECTING }

    data class DelayedState(val state: State, val delay: Long = DELAY_NOMINAL)

    private var state: DelayedState by MarkChangedDelegate(DelayedState(State.CONNECTING, DELAY_EXTENDED))

    private var deviceNetworkState: NetworkMonitor.State = NetworkMonitor.State.OFFLINE
    private var isReadyPayloadReceived: Boolean = false
    private var channelMessagesLoading: Boolean = false
    private var stateTriggeredDelay: Long = DELAY_EXTENDED
    private var stateTriggeredTimeMillis: Long? = null

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

    fun isConnected(): Boolean = state.state == State.ONLINE

    fun getState(): DelayedState = state

    fun observeState(): Observable<DelayedState> =
        observationDeck.connectRx(this, generator = { state }).distinctUntilChanged()

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

    @StoreThread
    fun handleBackgrounded(backgrounded: Boolean) {
        // Time spent backgrounded should not count against the visible delay budget.
        if (backgrounded) stateTriggeredDelay -= getStateActiveMillis()
        if (stateTriggeredTimeMillis != null) stateTriggeredTimeMillis = clock.currentTimeMillis()
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
        if (state.state != nextState || stateTriggeredTimeMillis == null) {
            stateTriggeredTimeMillis = clock.currentTimeMillis()
            stateTriggeredDelay = getNextStateDelayInitial(state, nextState)
        }
        state = DelayedState(nextState, getStateDelay(stateTriggeredDelay))
    }

    private fun getNextStateDelayInitial(currentState: DelayedState, nextState: State): Long = when (nextState) {
        State.ONLINE, State.OFFLINE, State.OFFLINE_AIRPLANE_MODE -> DELAY_NOMINAL
        // Coming back from a known-offline device resolves fast; a cold connect gets the long budget.
        State.CONNECTING -> {
            val wasOffline = currentState.state == State.OFFLINE ||
                currentState.state == State.OFFLINE_AIRPLANE_MODE
            if (wasOffline && getStateActiveMillis() > DELAY_NOMINAL) DELAY_NOMINAL else DELAY_EXTENDED
        }
    }

    private fun getStateActiveMillis(): Long =
        stateTriggeredTimeMillis?.let { clock.currentTimeMillis() - it } ?: 0L

    /** Remaining delay, quantised to [DELAY_INTERVAL] so equal states de-duplicate. */
    private fun getStateDelay(defaultDelay: Long): Long =
        ((defaultDelay - getStateActiveMillis()).toFloat() / DELAY_INTERVAL).roundToLong()
            .times(DELAY_INTERVAL)
            .coerceAtLeast(0L)

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
    }
}
