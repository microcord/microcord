package com.discord.stores

import com.discord.models.domain.ModelCall
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import rx.Observable

/**
 * Channels that are currently ringing *us*.
 *
 * A call is incoming while our user id appears in its ringing list. Answering (selecting the voice
 * channel), the call ending, or the caller cancelling all clear it. This is deliberately separate
 * from [StoreCalls]: the incoming-call UI must survive the call object being replaced, and it is the
 * only voice state a backgrounded client acts on.
 */
class StoreCallsIncoming(
    private val dispatcher: Dispatcher,
    private val userStore: StoreUser,
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) : StoreV2() {

    private val incomingCalls = HashSet<Long>()
    private var incomingCallSnapshot: Set<Long> = HashSet()

    @StoreThread
    private fun clearIncomingCall(channelId: Long) {
        if (incomingCalls.remove(channelId)) markChanged()
    }

    fun getIncomingCalls(): Set<Long> = incomingCallSnapshot

    @StoreThread
    fun handleCallCreateOrUpdate(call: ModelCall) {
        val channelId = call.channelId
        if (userStore.getMeInternal().id in call.ringingUserIds) {
            if (incomingCalls.add(channelId)) markChanged()
        } else {
            clearIncomingCall(channelId)
        }
    }

    @StoreThread
    fun handleCallDelete(callDelete: ModelCall) = clearIncomingCall(callDelete.channelId)

    @StoreThread
    fun handleVoiceChannelSelected(channelId: Long) = clearIncomingCall(channelId)

    fun observeHasIncoming(): Observable<Boolean> =
        observeIncoming().map(Set<Long>::isNotEmpty).distinctUntilChanged()

    fun observeIncoming(): Observable<Set<Long>> =
        observationDeck.connectRx(this) { getIncomingCalls() }.distinctUntilChanged()

    /** Dismisses a ring the user declined without waiting for the server to stop ringing. */
    fun removeIncomingCall(channelId: Long) = dispatcher.schedule { clearIncomingCall(channelId) }

    @StoreThread
    override fun snapshotData() {
        super.snapshotData()
        incomingCallSnapshot = HashSet(incomingCalls)
    }
}
